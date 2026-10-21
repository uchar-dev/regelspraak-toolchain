package dev.uchar.regelspraak.toolchain.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * One sentence of the explanation as a shallow tree of {@link Part}s, written by the {@link Verbaliser} through one
 * {@link Builder}. {@link #text()} is the concatenation of the texts of its parts and the only way to the plain
 * sentence, so the sentence and its parts cannot differ.
 */
public final class Sentence {

    private final List<Part> parts;
    private final String text;

    private Sentence(List<Part> parts) {
        this.parts = List.copyOf(parts);
        this.text = Part.concatenation(this.parts);
    }

    /** The parts at the root of the sentence, in order. */
    public List<Part> parts() { return parts; }

    /** The plain sentence: the texts of its parts after each other. */
    public String text() { return text; }

    @Override
    public String toString() { return text; }

    public static Builder builder() { return new Builder(); }

    /** Writes a sentence part by part. Consecutive glue merges into one {@link Part.Kind#TEXT}, and empty glue adds
     *  nothing. */
    public static final class Builder {

        private record Open(Part.Kind kind, Map<String, Object> meta, List<Part> parts) {}

        private final Deque<Open> open = new ArrayDeque<>();

        private Builder() {
            open.push(new Open(null, Map.of(), new ArrayList<>()));
        }

        /** Glue. */
        public Builder text(String glue) {
            if (glue.isEmpty()) return this;
            List<Part> parts = open.peek().parts();
            int last = parts.size() - 1;
            if (last >= 0 && parts.get(last).kind() == Part.Kind.TEXT)
                parts.set(last, Part.leaf(Part.Kind.TEXT, parts.get(last).text() + glue, Map.of()));
            else parts.add(Part.leaf(Part.Kind.TEXT, glue, Map.of()));
            return this;
        }

        /** A leaf of kind {@code kind} without meta; glue goes through {@link #text}. */
        public Builder leaf(Part.Kind kind, String text) {
            return leaf(kind, text, Map.of());
        }

        public Builder leaf(Part.Kind kind, String text, Map<String, Object> meta) {
            if (kind == Part.Kind.TEXT) throw new IllegalArgumentException("lijm gaat via text(...)");
            open.peek().parts().add(Part.leaf(kind, text, meta));
            return this;
        }

        /** Opens a group of kind {@code kind}; the parts that follow are its own until {@link #close}. */
        public Builder open(Part.Kind kind, Map<String, Object> meta) {
            if (!kind.isGroup()) throw new IllegalArgumentException(kind + " is een blad, geen groep");
            open.push(new Open(kind, meta, new ArrayList<>()));
            return this;
        }

        /** Closes the innermost open group, which must be of kind {@code kind}. */
        public Builder close(Part.Kind kind) {
            Open g = open.peek();
            if (g.kind() == null)
                throw new IllegalStateException("de zin sluit een groep " + kind + " die niet open is");
            if (g.kind() != kind)
                throw new IllegalStateException("de zin sluit een groep " + kind + ", en de open groep is " + g.kind());
            open.pop();
            open.peek().parts().add(Part.group(kind, g.meta(), g.parts()));
            return this;
        }

        public Sentence build() {
            if (open.size() != 1)
                throw new IllegalStateException("de zin heeft nog een open groep " + open.peek().kind());
            return new Sentence(open.peek().parts());
        }
    }
}
