package io.github.yhavinga.regelspraak;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The clamp on the {@code reden == null} convention (maintainer decision 2026-09-19, lens round of the
 * same date).
 *
 * <p><b>What it guards.</b> Since the card, every {@code Onopgelost} carries a {@code reden} and the
 * resolver's survivor sweep reports each surviving marker whose reden is non-null. A reden of null means
 * "the marker's diagnosis is raised elsewhere" — by the producing route itself, by its caller, or by the
 * route that replaces it. The compiler forces every NEW production site to pass SOMETHING; it cannot
 * force that something to be a reason. The lens measured the hole: mutating one site's reden to null
 * drops exactly one of the suite's tests and leaves everything else green, so a future site that writes
 * null would be silent again — the very class this card closed.
 *
 * <p><b>Why a source pin and not a runtime heuristic.</b> The alternative was a runtime clamp: a
 * surviving null-reden marker in a model whose diagnostics list is EMPTY is a proven silence, so raise a
 * generic WARNING. That is cheap but heuristic — one loud diagnostic anywhere else in the document
 * masks the silence, and a document with a syntax error masks all of them. This pin is exact instead,
 * and it follows the house pattern of the gate scripts: a reason-carrying list of sanctioned entries
 * that fails when a new one appears AND when a listed one disappears. The cost is maintenance, and that
 * cost is the point — adding a null is then a deliberate act with a written reason, exactly as raising a
 * gate floor is.
 *
 * <p><b>How it reads the source.</b> Line numbers drift, so nothing here pins one: the unit is
 * (file, enclosing method, count). String literals and line comments are blanked before scanning, since
 * the word "onopgelost" occurs in the prose of several diagnostics.
 */
class MarkerRedenPinTest {

    /** One sanctioned family of {@code reden == null} constructions: all of them in {@code methode} of
     *  {@code bestand}, with the reason that route needs no survivor report. */
    private record Plek(String bestand, String methode, int aantal, String waarom) {}

    /** The thirteen sanctioned null-constructions, measured 2026-09-19. Growth is NOT free here (unlike
     *  a gate floor): a null is a promise that something else speaks, so every new one is listed with
     *  the diagnostic that keeps that promise. */
    private static final List<Plek> GESANCTIONEERD = List.of(
            new Plek("RuleChecker.java", "keurGeheelPeriode", 1,
                    "de §8.2-ERROR die de regel erboven opwerpt IS de diagnose van deze marker"),
            new Plek("RuleChecker.java", "dagsoortOperandFout", 1,
                    "meldPredicaat werpt de Tabel-57-ERROR op; die is de diagnose van deze marker"),
            new Plek("ExpressieBouwer.java", "bouwFunctie", 2,
                    "beide markers staan direct onder hun eigen WARNING — de aggregatie over een "
                    + "ongedeclareerd lid, en de niet-omgezette dimensieselectie / 'gedurende de tijd dat'"),
            new Plek("ExpressieBouwer.java", "keurVouwbron", 4,
                    "elk van de vier vouwbron-weigeringen zet haar eigen WARNING neer vlak voor de marker"),
            new Plek("ExpressieBouwer.java", "aggregatieFilter", 3,
                    "de enige aanroeper (bouwFunctie) toetst het resultaat op Onopgelost en waarschuwt; "
                    + "de sweep zou dat herhalen"),
            new Plek("ExpressieBouwer.java", "keurRangtelwoord", 2,
                    "de §5.5.3-ERROR respectievelijk -WARNING staat vlak boven de marker"));

    /** A marker construction: the two record constructors and the two {@code onopgelost(…)} helpers. */
    private static final Pattern BOUW =
            Pattern.compile("\\b(?:new\\s+(?:Conditie|Expr)\\.Onopgelost|onopgelost)\\s*\\(");

    /** A class-member declaration: a non-blank line at member indentation whose first identifier before
     *  a '(' names it. Deeper constructs (if/for/switch) sit at 8 spaces or more and never match. */
    private static final Pattern LID = Pattern.compile("^ {4}\\S.*?\\b(\\w+)\\s*\\(");

    /** A floor on what the scanner sees, so a regex that stops matching cannot pass in silence — the
     *  same reasoning the gate scripts' EXPECTED_MIN constants carry. Measured 2026-09-19: 32. */
    private static final int MINIMAAL_AANTAL_BOUWPLEKKEN = 30;

    @Test void elke_null_reden_staat_op_de_gesanctioneerde_lijst() throws IOException {
        Path wortel = Path.of("src/main/java");
        assertTrue(Files.isDirectory(wortel),
                () -> "de bronboom " + wortel.toAbsolutePath() + " is er niet — deze test moet vanuit "
                        + "frontends/regelspraak draaien (zo draait `make test` hem)");

        Map<String, Integer> gevonden = new TreeMap<>();
        List<String> leegteFouten = new ArrayList<>();
        int bouwplekken = 0;
        try (Stream<Path> paden = Files.walk(wortel)) {
            for (Path p : paden.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                String ruw = Files.readString(p, StandardCharsets.UTF_8);
                String schoon = zonderTekstEnCommentaar(ruw);
                String[] regels = ruw.split("\n", -1);
                int[] start = regelStarts(regels);
                Matcher m = BOUW.matcher(schoon);
                while (m.find()) {
                    int regel = regelVan(start, m.start());
                    if (regels[regel].contains("private Expr onopgelost")) continue;   // the helper itself
                    bouwplekken++;
                    String laatste = laatsteArgument(schoon, m.end() - 1);
                    if (laatste.isEmpty() || "\"\"".equals(laatste))
                        leegteFouten.add(p.getFileName() + ":" + (regel + 1) + " draagt een lege reden");
                    if (!"null".equals(laatste)) continue;
                    gevonden.merge(p.getFileName() + "#" + omvattendLid(regels, regel), 1, Integer::sum);
                }
            }
        }

        assertTrue(leegteFouten.isEmpty(),
                () -> "een lege reden is een stille marker met extra stappen: " + leegteFouten);
        int gemeten = bouwplekken;
        assertTrue(gemeten >= MINIMAAL_AANTAL_BOUWPLEKKEN,
                () -> "de scanner vond maar " + gemeten + " markerconstructies (vloer "
                        + MINIMAAL_AANTAL_BOUWPLEKKEN + ") — de patronen sluiten niet meer aan op de bron, "
                        + "en dan keurt deze klem alles goed zonder iets te lezen");

        Map<String, Integer> verwacht = new LinkedHashMap<>();
        Map<String, String> waarom = new LinkedHashMap<>();
        for (Plek p : GESANCTIONEERD) {
            verwacht.put(p.bestand() + "#" + p.methode(), p.aantal());
            waarom.put(p.bestand() + "#" + p.methode(), p.waarom());
        }

        List<String> nieuw = gevonden.keySet().stream().filter(k -> !verwacht.containsKey(k)).toList();
        assertTrue(nieuw.isEmpty(),
                () -> "nieuwe reden==null-plek(ken) zonder vermelding: " + nieuw + ". Een null belooft dat "
                        + "een andere melding deze marker dekt; zet die belofte in GESANCTIONEERD of geef "
                        + "de marker een reden.");

        List<String> weg = verwacht.keySet().stream().filter(k -> !gevonden.containsKey(k)).toList();
        assertTrue(weg.isEmpty(),
                () -> "de lijst is verouderd — deze plek(ken) bouwen geen null-marker meer: " + weg);

        for (var e : verwacht.entrySet())
            assertEquals(e.getValue(), gevonden.get(e.getKey()),
                    () -> e.getKey() + " telt " + gevonden.get(e.getKey()) + " null-markers in plaats van "
                            + e.getValue() + " — gesanctioneerd omdat: " + waarom.get(e.getKey()));
    }

    // ---------------- source scanning ----------------

    /** {@code bron} with every string literal and line comment blanked to spaces (offsets preserved):
     *  several diagnostic texts contain the word "onopgelost" followed by a '(' section reference, and a
     *  scanner that read those would attribute a construction to a sentence. */
    private static String zonderTekstEnCommentaar(String bron) {
        char[] uit = bron.toCharArray();
        for (int i = 0; i < uit.length; ) {
            if (uit[i] == '"') {
                int j = i + 1;
                while (j < uit.length && !(uit[j] == '"' && uit[j - 1] != '\\')) uit[j++] = ' ';
                i = j + 1;
            } else if (uit[i] == '/' && i + 1 < uit.length && uit[i + 1] == '/') {
                int j = i;
                while (j < uit.length && uit[j] != '\n') uit[j++] = ' ';
                i = j;
            } else i++;
        }
        return new String(uit);
    }

    /** The last argument of the call whose '(' sits at {@code haakje}, trimmed — parenthesis-balanced,
     *  so a nested {@code bron(f)} does not end the scan. */
    private static String laatsteArgument(String s, int haakje) {
        int diepte = 0, i = haakje, laatsteKomma = haakje;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') diepte++;
            else if (c == ')') { if (--diepte == 0) break; }
            else if (c == ',' && diepte == 1) laatsteKomma = i;
        }
        assertTrue(diepte == 0, () -> "ongebalanceerde haakjes vanaf positie " + haakje);
        return s.substring(laatsteKomma == haakje ? haakje + 1 : laatsteKomma + 1, i).trim();
    }

    /** The name of the class member that encloses {@code regel} — the nearest declaration above it. */
    private static String omvattendLid(String[] regels, int regel) {
        for (int k = regel; k >= 0; k--) {
            Matcher m = LID.matcher(regels[k]);
            if (m.find()) return m.group(1);
        }
        return "<buiten een lid>";
    }

    private static int[] regelStarts(String[] regels) {
        int[] start = new int[regels.length];
        for (int i = 1, off = regels[0].length() + 1; i < regels.length; off += regels[i].length() + 1, i++)
            start[i] = off;
        return start;
    }

    private static int regelVan(int[] start, int positie) {
        int lo = 0, hi = start.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (start[mid] <= positie) lo = mid; else hi = mid - 1;
        }
        return lo;
    }
}
