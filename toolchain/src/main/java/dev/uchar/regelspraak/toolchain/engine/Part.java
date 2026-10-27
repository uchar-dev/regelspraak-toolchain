package dev.uchar.regelspraak.toolchain.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One part of a sentence of the explanation ({@link Sentence}): a leaf with its text, or a group of parts, whose text
 * is by definition the concatenation of the texts of its parts. {@code meta} carries what the kind adds beside the
 * text (the declaration a {@link Kind#NAME} names, the verdict of a {@link Kind#CONDITION}, ...), as strings, booleans,
 * integers, lists of strings or null, in the order the consumer reads them.
 */
public record Part(Kind kind, String text, Map<String, Object> meta, List<Part> parts) {

    /** What a part is. The groups are {@link #CONDITION}, {@link #MEMBERS}, {@link #COMPUTATION}, {@link #VARIANT},
     *  {@link #READING} and {@link #GROUND}; every other kind is a leaf. {@link #TEXT} is the glue: every character that belongs to no other
     *  kind. */
    public enum Kind {
        /** The label of an instance, or the name of an object a creation made ({@link Verbaliser}): the subject of
         *  the sentence, or a member of a quantifier fold. */
        SUBJECT,
        /** A declared name as {@link Naming} names it, with {@code declaration}, {@code owner} and {@code canonical}
         *  where the declaration is known, and {@code object}, the label of the object whose value of an attribute or
         *  characteristic the sentence names, null where a link on the walk of the read is absent. */
        NAME,
        /** Rule text as written, with the three keys of {@link #NAME} where the operand reads one declaration, and
         *  {@code object} as on {@link #NAME}. */
        QUOTE,
        /** A value as the engine read it, with its unit; {@code empty: true} for an empty one, and
         *  {@code timeline: true} for "een tijdlijn", the value of a timeline target, which the sentence does not
         *  spell out. */
        VALUE,
        /** The name of a rule or of a variant of a defaults group, without its quotes. */
        RULE,
        /** A word that carries the logic between the parts: omdat, want, maar, en, of, en niet, niet geldt, gold
         *  niet. */
        CONNECTIVE,
        /** Glue. */
        TEXT,
        /** A condition: {@code holds}, the verdict of the engine on its node, and on a compound or a fold its
         *  {@code quantifier} (the wire spelling of the IR) with the {@code threshold} of a count. */
        CONDITION,
        /** The members of a quantifier fold that carry its outcome, with the colon before them: {@code members} (m, the
         *  members with a verdict),
         *  {@code holding} (k, those for which its matrix holds) and {@code unjudged} (those whose matrix faulted). Of
         *  a fold in a computation, the members it folded over, each with what it contributed: {@code members} (m)
         *  alone. */
        MEMBERS,
        /** How the rule of a computed value computed it, after the rule's name, with its colon: the rule text of its
         *  value, every value the computation read or folded with the value of its node (a fold with its
         *  {@link #MEMBERS}), and the value before a rounding and before a bound. */
        COMPUTATION,
        /** A writer that did not write the value of its sentence, with the separator before it ("; ", or ", " between
         *  displaced variants): {@code decided: false}, and {@code displaced: true} for a variant after the decider of a
         *  defaults group and for an initialisation that was not at its turn. In the sentence of a defaults group every
         *  variant but the decider; in that of a computed value every writer but the one that wrote. A displaced variant
         *  of a defaults group says what it found: {@code holds}, its value as a {@link #VALUE}, {@code decision} for a
         *  characteristic, {@code unreadable} where its reading carried a fault. */
        VARIANT,
        /** The value the engine read for the operand or the member right before it, with its brackets: " (", the
         *  {@link #VALUE}, ")". */
        READING,
        /** The ground of the sentence, its last part: the {@code // Bron:} annotations of the rule or of the default
         *  that decided, parsed ({@link Ground}), with that decider in the terms of the IR: {@code rule}, the name of
         *  the rule's form, or {@code group}, {@code pointer} (the default in its group), {@code position} (its place
         *  in its chain, 1-based) and {@code variant} (its name, where it has one). */
        GROUND,
        /** A Juriconnect anchor, with {@code bwb}, {@code article}, and {@code paragraph} and {@code part} where it
         *  names them. */
        STATUTE,
        /** The slug of a lemma. */
        LEMMA,
        /** The slug of a deviation, with {@code between}, the layers it lies between in the order of the register (the
         *  first is the layer the second deviates from), or the one layer it lies within, and {@code follows}, the layer
         *  the rule set follows, absent for a deviation within one layer. */
        DEVIATION,
        /** A segment of an annotation that matches no other pattern: a quotation, a free note. */
        NOTE;

        public boolean isGroup() {
            return this == CONDITION || this == MEMBERS || this == COMPUTATION || this == VARIANT || this == READING
                    || this == GROUND;
        }
    }

    public Part {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(parts, "parts");
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            Object v = e.getValue();
            if (v instanceof List<?> l) {
                for (Object o : l)
                    if (!(o instanceof String))
                        throw new IllegalArgumentException("het stuk " + kind + " draagt bij " + e.getKey()
                                + " een lijst met " + (o == null ? "null" : "een " + o.getClass().getSimpleName())
                                + ", en een lijst in een stuk draagt alleen teksten");
                v = List.copyOf(l);
            } else if (v != null && !(v instanceof String) && !(v instanceof Boolean) && !(v instanceof Integer))
                throw new IllegalArgumentException("het stuk " + kind + " draagt bij " + e.getKey() + " een "
                        + v.getClass().getSimpleName() + ", en een stuk draagt alleen teksten, booleans, gehele getallen,"
                        + " lijsten van teksten en null");
            copy.put(e.getKey(), v);
        }
        meta = Collections.unmodifiableMap(copy);
        parts = List.copyOf(parts);
        if (!kind.isGroup() && !parts.isEmpty())
            throw new IllegalArgumentException("het blad " + kind + " heeft stukken");
        if (kind.isGroup() && !text.equals(concatenation(parts)))
            throw new IllegalArgumentException("de tekst van de groep " + kind + " is niet die van haar stukken");
    }

    /** A leaf. */
    public static Part leaf(Kind kind, String text, Map<String, Object> meta) {
        if (kind.isGroup()) throw new IllegalArgumentException(kind + " is een groep, geen blad");
        return new Part(kind, text, meta, List.of());
    }

    /** A group, with the text of its parts. */
    public static Part group(Kind kind, Map<String, Object> meta, List<Part> parts) {
        if (!kind.isGroup()) throw new IllegalArgumentException(kind + " is een blad, geen groep");
        return new Part(kind, concatenation(parts), meta, parts);
    }

    /** {@code keyValues} as meta: a key, its value, the next key, ...; a value may be null. */
    public static Map<String, Object> meta(Object... keyValues) {
        if (keyValues.length % 2 != 0) throw new IllegalArgumentException("meta neemt sleutel-waardeparen");
        Map<String, Object> sink = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) sink.put((String) keyValues[i], keyValues[i + 1]);
        return sink;
    }

    static String concatenation(List<Part> parts) {
        StringBuilder b = new StringBuilder();
        for (Part p : parts) b.append(p.text());
        return b.toString();
    }
}
