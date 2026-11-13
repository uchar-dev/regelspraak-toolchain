package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The comment above a declaration is the start of its description in the OpenAPI document. Own Jutterswet material
 * ({@code src/test/resources/comments/}) writes a comment above an object type, an attribute, a characteristic and a
 * parameter, and none above others of each kind; the canonical document and that of the vocabulary {@code handboek}
 * are byte-pinned under {@code src/test/resources/openapi/} and written to {@code target/openapi/}.
 *
 * <p>Beside the pins: the document of the same source without its comment lines differs from it in exactly the four
 * descriptions, each of which is the comment, a full stop where it has none, and the description without it; and a
 * model read from the {@code uchar-ir-v1} document of the source, which carries no comment, gives that document
 * without comments.
 */
class OpenApiCommentTest {

    static final Path DIR = TokaSource.REPO.resolve("service/src/test/resources/comments");
    static final Path SOURCE = DIR.resolve("jutterswet-commentaar.rgs");
    static final Path DESCRIPTOR_FILE = DIR.resolve("jutterswet-commentaar.service.json");

    private static final String OBJECT_TYPE_COMMENT = "Bron: Jutterswet art. 2 · lemma's: jutter iemand die het strand"
            + " afzoekt naar aangespoelde voorwerpen";
    private static final String ATTRIBUTE_COMMENT = "het totaal van de premies van de vondsten die de jutter aanmeldde";
    private static final String CHARACTERISTIC_COMMENT = "Bron: Jutterswet art. 5 lid 2 · lemma's: premiewaardig";
    private static final String PARAMETER_COMMENT = "Bron: Jutterswet art. 3 lid 1 · lemma's: vondsttarief het bedrag"
            + " per kilogram dat de gemeente voor een vondst betaalt";

    private static ServiceDescriptor descriptor;
    private static RegelSpraakModel model;
    private static String source;

    @BeforeAll
    static void read() throws IOException {
        source = Files.readString(SOURCE);
        model = Resolver.resolve(source);
        assertEquals(List.of(), model.errors());
        descriptor = DescriptorLoader.MAPPER.readValue(Files.readString(DESCRIPTOR_FILE), ServiceDescriptor.class);
    }

    private static void byteEqual(String vocabulary) throws IOException {
        Path target = Path.of("target", "openapi", OpenApiEmitter.fileName(descriptor, vocabulary));
        OpenApiEmitter.write(descriptor, model, vocabulary, target);
        String name = OpenApiEmitter.fileName(descriptor, vocabulary).replace(".json", ".golden.json");
        Path golden = TokaSource.REPO.resolve("service/src/test/resources/openapi").resolve(name);
        String expected = Files.readString(golden);
        String actual = new String(OpenApiEmitter.toBytes(OpenApiEmitter.emit(descriptor, model, vocabulary)),
                StandardCharsets.UTF_8);
        if (expected.equals(actual)) return;
        int i = 0;
        while (i < Math.min(expected.length(), actual.length()) && expected.charAt(i) == actual.charAt(i)) i++;
        fail(name + " wijkt af op teken " + i + ": golden '" + expected.substring(i, Math.min(expected.length(), i + 120))
                + "', document '" + actual.substring(i, Math.min(actual.length(), i + 120)) + "'; een bewuste wijziging:"
                + " schrijf de golden dan opnieuw uit " + target);
    }

    @Test
    void the_canonical_document_is_byte_equal_to_the_golden() throws IOException { byteEqual(null); }

    @Test
    void the_document_of_the_handbook_is_byte_equal_to_the_golden() throws IOException { byteEqual("handboek"); }

    /** Each of the four descriptions starts with the comment above its declaration, verbatim, its lines joined with a
     *  space. */
    @Test
    void the_description_starts_with_the_comment_above_the_declaration() {
        ObjectNode doc = OpenApiEmitter.emit(descriptor, model);
        startsWith(doc, "/components/schemas/Jutter/description", OBJECT_TYPE_COMMENT + ". Objecttype 'Jutter'.");
        startsWith(doc, "/components/schemas/Jutter/properties/vondstbedrag/description", ATTRIBUTE_COMMENT);
        startsWith(doc, "/components/schemas/Jutter/properties/premiewaardig/description",
                CHARACTERISTIC_COMMENT + ". characteristic");
        startsWith(doc, "/components/schemas/Request/properties/parameters/description",
                "'vondsttarief': " + PARAMETER_COMMENT + ". parameternaam -> waarde;");
    }

    /** In a vocabulary the comment stays in front, the generated description in that vocabulary's names behind it, and
     *  the sentence of the other spellings last. */
    @Test
    void in_a_vocabulary_the_comment_stays_in_front_of_the_generated_description() {
        ObjectNode doc = OpenApiEmitter.emit(descriptor, model, "handboek");
        startsWith(doc, "/components/schemas/Strandjutter/description",
                OBJECT_TYPE_COMMENT + ". Objecttype 'strandjutter'.");
        startsWith(doc, "/components/schemas/Strandjutter/properties/totale premie/description",
                ATTRIBUTE_COMMENT + ". De dienst neemt ook 'vondstbedrag' (canonical) aan.");
        startsWith(doc, "/components/schemas/Request/properties/parameters/description",
                "'tarief per kilogram': " + PARAMETER_COMMENT + ".");
    }

    /** Without its comment lines the source gives a document that differs in the four descriptions only, each the
     *  comment, a full stop, and the description of the source without comments. */
    @Test
    void the_comment_changes_only_the_descriptions_of_the_commented_declarations() {
        StringBuilder bare = new StringBuilder();
        for (String line : source.split("\n", -1))
            if (!line.strip().startsWith("//")) bare.append(line).append('\n');
        RegelSpraakModel without = Resolver.resolve(bare.toString());
        assertEquals(List.of(), without.errors());
        Map<String, String> differences = new LinkedHashMap<>();
        compare("", OpenApiEmitter.emit(descriptor, model), OpenApiEmitter.emit(descriptor, without), differences);
        Map<String, String> comments = new LinkedHashMap<>();
        comments.put("/components/schemas/Jutter/properties/vondstbedrag/description", ATTRIBUTE_COMMENT);
        comments.put("/components/schemas/Jutter/properties/premiewaardig/description", CHARACTERISTIC_COMMENT);
        comments.put("/components/schemas/Jutter/description", OBJECT_TYPE_COMMENT);
        comments.put("/components/schemas/Request/properties/parameters/description",
                "'vondsttarief': " + PARAMETER_COMMENT);
        assertEquals(new ArrayList<>(comments.keySet()), new ArrayList<>(differences.keySet()), differences::toString);
        ObjectNode bareDoc = OpenApiEmitter.emit(descriptor, without);
        for (var e : comments.entrySet())
            assertEquals(e.getValue() + ". " + bareDoc.at(e.getKey()).asText(), differences.get(e.getKey()), e.getKey());
    }

    /** A service started on the {@code uchar-ir-v1} document of the source ({@code serve <document.json>}) has no
     *  comment: its document is the one of the source without comment lines. */
    @Test
    void a_service_started_on_the_ir_document_gives_no_comment() throws Exception {
        DecisionCore core = DecisionCore.buildFromDocument(Files.readString(DESCRIPTOR_FILE), model.document(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null);
        StringBuilder bare = new StringBuilder();
        for (String line : source.split("\n", -1))
            if (!line.strip().startsWith("//")) bare.append(line).append('\n');
        assertEquals(new String(OpenApiEmitter.toBytes(OpenApiEmitter.emit(core.descriptor(),
                        Resolver.resolve(bare.toString()))), StandardCharsets.UTF_8),
                new String(OpenApiEmitter.toBytes(OpenApiEmitter.emit(core.descriptor(), core.model())),
                        StandardCharsets.UTF_8));
    }

    private static void startsWith(JsonNode doc, String pointer, String start) {
        String description = doc.at(pointer).asText();
        assertTrue(description.startsWith(start), pointer + ": '" + description + "' begint niet met '" + start + "'");
    }

    /** Every place where the two trees differ, by JSON pointer, with the value in {@code with}; both trees must have the
     *  same shape, fields in the same order. */
    private static void compare(String at, JsonNode with, JsonNode without, Map<String, String> differences) {
        if (with.isObject() && without.isObject()) {
            List<String> a = new ArrayList<>(), b = new ArrayList<>();
            with.fieldNames().forEachRemaining(a::add);
            without.fieldNames().forEachRemaining(b::add);
            assertEquals(b, a, "de velden van " + at);
            for (String f : a) compare(at + "/" + f.replace("~", "~0").replace("/", "~1"), with.get(f), without.get(f),
                    differences);
        } else if (with.isArray() && without.isArray()) {
            assertEquals(without.size(), with.size(), "de lengte van " + at);
            Iterator<JsonNode> x = with.elements(), y = without.elements();
            for (int i = 0; x.hasNext(); i++) compare(at + "/" + i, x.next(), y.next(), differences);
        } else if (!with.equals(without)) {
            differences.put(at, with.asText());
        }
    }
}
