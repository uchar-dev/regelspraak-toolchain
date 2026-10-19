package dev.uchar.regelspraak.toolchain.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A message or OpenAPI description that names a JSON key, query parameter or field names the one the code reads
 * (README, "Contract change"). The test reads the string literals of the main sources of the toolchain, the service
 * and the Quarkus adapter and refuses the old names in the forms that name a key: between quotes or backticks, as
 * the opening of an object shape, as the subject of "ontbreekt"/"moet", and after "queryparameter". A plain Dutch word
 * that is no key ("een waarde van het domein") stays outside these forms, so there is no list of exceptions.
 */
class ContractNamesInMessagesTest {

    private static final String OLD_KEYS = "instanties|attributen|kenmerken|rollen|relaties|feittype|peildatum|"
            + "parametersets|waarden|waarde|vanaf|tot|eenheid|uitleg|dekking|fixpunt|vocabulaire|invoervocabulaire|"
            + "onderwerp|schendingen|fouten|fout|veld|soort|reden|inconsistenties|tijdlijn|naam|pad|invoer|uitvoer|"
            + "dienst|regelnamen|regelgroepen";

    private static final List<Pattern> NAMING_A_KEY = List.of(
            Pattern.compile("['`]\\b(" + OLD_KEYS + ")\\b['`]"),
            Pattern.compile("\\{\\\\?\"?(waarde|vanaf|tot|eenheid)\\b"),
            Pattern.compile("\\b(instanties|attributen|kenmerken|relaties|peildatum|parametersets|invoervocabulaire)\\b"
                    + " (ontbreekt|moet|is verplicht)"),
            Pattern.compile("queryparameter\\w* (invoervocabulaire|vocabulaire|uitleg|dekking|fixpunt)"),
            Pattern.compile("\\.(naam|waarden|waarde) ontbreekt"));

    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    @Test
    void literalsNameNoOldKey() throws IOException {
        List<String> found = new ArrayList<>();
        int scanned = 0;
        for (String module : new String[]{"src/main", "../service/src/main", "../service-quarkus/src/main"}) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(Path.of(module))) {
                files = walk.filter(p -> p.toString().endsWith(".java")).toList();
            }
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file);
                scanned += lines.size();
                for (int i = 0; i < lines.size(); i++) {
                    Matcher literal = LITERAL.matcher(lines.get(i));
                    while (literal.find())
                        for (Pattern p : NAMING_A_KEY)
                            if (p.matcher(literal.group(1)).find())
                                found.add(file + ":" + (i + 1) + ": " + literal.group(1));
                }
            }
        }
        assertTrue(scanned > 5000, "the scan read " + scanned + " lines; the paths are wrong");
        assertTrue(found.isEmpty(), "a literal names an old key (README, \"Contract change\"):\n"
                + String.join("\n", found));
    }
}
