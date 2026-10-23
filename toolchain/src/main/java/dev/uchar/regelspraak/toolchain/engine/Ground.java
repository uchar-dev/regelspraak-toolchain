package dev.uchar.regelspraak.toolchain.engine;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parser of the ground of a sentence of the explanation: the {@code // Bron:} annotations of a rule or of the
 * block of a default (uchar's IR-SPEC ch. 3.1 and 6.1, WF-082, {@code statuteAnnotations}: one string per annotation
 * line, each parsed apart), as parts ({@link Part.Kind#STATUTE}, {@link Part.Kind#LEMMA}, {@link Part.Kind#DEVIATION},
 * {@link Part.Kind#NOTE} and glue). The structure is a convention of the rule set, not of uchar or RegelSpraak (the werkbank's MODELKAART B20: "permalink
 * met verhouding · anker(s) · lemma's: slug · slug"), so the parser reads the text, and it is total: an annotation is
 * free text (provenance only), and a segment that matches no pattern is a {@code NOTE}. The grammar is in
 * {@code docs/ENGINE.md}, "The parts of an explanation".
 */
final class Ground {

    private Ground() {}

    /** Between two segments of an annotation. */
    static final String SEGMENT_SEPARATOR = " · ";
    /** Between two annotations of a rule or of a default. */
    static final String ANNOTATION_SEPARATOR = "; ";

    private static final String SLUG = "[\\p{L}\\p{N}-]+";
    /** A layer of the wetsanalyse (wet, hb, brm): the werkbank chooses the codes, so the parser reads any. */
    private static final String LAYER = "\\p{Ll}+";
    private static final Pattern STATUTE =
            Pattern.compile("(BWBR[0-9]{7})&artikel=([^&]+)(?:&lid=([^&]+))?(?:&o=([^&]+))?");
    private static final Pattern LEMMA_HEAD = Pattern.compile("(lemma's: )(" + SLUG + ")");
    private static final Pattern DEVIATION = Pattern.compile("(deviatie (?:(" + LAYER + ")/(" + LAYER + "), volgt ("
            + LAYER + ")|binnen (" + LAYER + ")): )(" + SLUG + ")");
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
     *  list of lemmas; after it a slug is a lemma, and {@code deviatie <layer>/<layer>, volgt <layer>: <slug>} or
     *  {@code deviatie binnen <layer>: <slug>} a deviation. Whether the followed layer is one of the pair, and whether
     *  the deviation is in the register, is the werkbank's lint; the parser reads the form. */
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
                b.text(m.group(1)).leaf(Part.Kind.DEVIATION, m.group(6), m.group(5) == null
                        ? Part.meta("between", List.of(m.group(2), m.group(3)), "follows", m.group(4))
                        : Part.meta("between", List.of(m.group(5))));
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
