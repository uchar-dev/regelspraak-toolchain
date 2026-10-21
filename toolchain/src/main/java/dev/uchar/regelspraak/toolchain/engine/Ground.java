package dev.uchar.regelspraak.toolchain.engine;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parser of the ground of a sentence of the explanation: the {@code // Bron:} annotations of a rule (uchar's
 * IR-SPEC ch. 3.1, WF-082, one string per annotation) or the {@code Default.source} of a default, as parts
 * ({@link Part.Kind#STATUTE}, {@link Part.Kind#LEMMA}, {@link Part.Kind#DEVIATION}, {@link Part.Kind#NOTE} and glue).
 * The structure is a convention of the rule set, not of uchar or RegelSpraak (the werkbank's MODELKAART B20: "permalink
 * met verhouding · anker(s) · lemma's: slug · slug"), so the parser reads the text, and it is total: an annotation is
 * free text (provenance only), and a segment that matches no pattern is a {@code NOTE}. The grammar is in
 * {@code docs/ENGINE.md}, "The parts of an explanation".
 *
 * <p>A default carries its annotations already joined with "; " ({@code Default.source}, as uchar's frontend writes
 * it), and "; " also occurs within a note, so the parser takes that string as one annotation: a "; " between two
 * annotations of a default stays inside a segment.
 */
final class Ground {

    private Ground() {}

    /** Between two segments of an annotation. */
    static final String SEGMENT_SEPARATOR = " · ";
    /** Between two annotations of a rule, as {@code Default.source} joins those of a default. */
    static final String ANNOTATION_SEPARATOR = "; ";

    private static final String SLUG = "[\\p{L}\\p{N}-]+";
    private static final Pattern STATUTE =
            Pattern.compile("(BWBR[0-9]{7})&artikel=([^&]+)(?:&lid=([^&]+))?(?:&o=([^&]+))?");
    private static final Pattern LEMMA_HEAD = Pattern.compile("(lemma's: )(" + SLUG + ")");
    private static final Pattern DEVIATION = Pattern.compile("(afwijking(, niet gevolgd)?: )(" + SLUG + ")");
    private static final Pattern LEMMA = Pattern.compile(SLUG);

    /** Writes {@code annotations}, each non-empty, as parts, with {@link #ANNOTATION_SEPARATOR} as glue between
     *  them. */
    static void write(Sentence.Builder b, List<String> annotations) {
        for (int i = 0; i < annotations.size(); i++) {
            if (i > 0) b.text(ANNOTATION_SEPARATOR);
            annotation(b, annotations.get(i));
        }
    }

    /** One annotation: its segments between {@link #SEGMENT_SEPARATOR}s. A segment {@code lemma's: <slug>} opens the
     *  list of lemmas; after it a slug is a lemma and {@code afwijking: <slug>} or {@code afwijking, niet gevolgd:
     *  <slug>} a deviation. */
    private static void annotation(Sentence.Builder b, String annotation) {
        String[] segments = annotation.split(Pattern.quote(SEGMENT_SEPARATOR), -1);
        boolean lemmas = false;
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) b.text(SEGMENT_SEPARATOR);
            String s = segments[i];
            if (s.isEmpty()) continue;
            Matcher m;
            if ((m = STATUTE.matcher(s)).matches()) {
                b.leaf(Part.Kind.STATUTE, s, statute(m));
            } else if ((m = LEMMA_HEAD.matcher(s)).matches()) {
                lemmas = true;
                b.text(m.group(1)).leaf(Part.Kind.LEMMA, m.group(2));
            } else if (lemmas && (m = DEVIATION.matcher(s)).matches()) {
                b.text(m.group(1)).leaf(Part.Kind.DEVIATION, m.group(3), Part.meta("followed", m.group(2) == null));
            } else if (lemmas && LEMMA.matcher(s).matches()) {
                b.leaf(Part.Kind.LEMMA, s);
            } else {
                b.leaf(Part.Kind.NOTE, s);
            }
        }
    }

    private static Map<String, Object> statute(Matcher m) {
        Map<String, Object> meta = Part.meta("bwb", m.group(1), "article", m.group(2));
        if (m.group(3) != null) meta.put("paragraph", m.group(3));
        if (m.group(4) != null) meta.put("part", m.group(4));
        return meta;
    }
}
