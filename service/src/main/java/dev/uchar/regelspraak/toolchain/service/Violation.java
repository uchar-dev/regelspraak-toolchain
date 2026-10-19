package dev.uchar.regelspraak.toolchain.service;

/**
 * A typed validation violation (a <i>schending</i>) — the single currency of every fail-fast check in the
 * service core. It carries the KIND of problem, a machine-usable FIELD PATH naming exactly what was wrong
 * ({@code "instances[0].attributes.leeftijd"}, {@code "entrypoints[2].scope.ruleNames[0]"},
 * {@code "parameters.volwassenleeftijd"}), and a human-readable message. Fase 3 turns a request-mapping
 * violation into a 400 whose body names {@link #field()}, and a startup (descriptor/marker) violation into a
 * refused start naming the offending value.
 *
 * @param soort   the category of violation
 * @param veld    the field path that located the problem (dot/bracket notation), never null (may be "")
 * @param bericht the human diagnostic, always naming the offending value
 */
public record Violation(Kind kind, String field, String message) {

    public enum Kind {
        /** A descriptor references a regel/regelgroep/objecttype/attribuut/kenmerk/parameter the model lacks. */
        UNKNOWN_REFERENCE,
        /** An in-scope construct is a marker (not lowered / overgeslagen) and markers are not allowed. */
        MARKER,
        /** A request field names an unknown objecttype/attribuut/kenmerk/parameter/rol. */
        UNKNOWN_FIELD,
        /** A request value has the wrong datatype (a string where a number is due, a bad date, …), or an id on a
         *  rol names an instance whose objecttype does not fill that rol. */
        WRONG_TYPE,
        /** An eenheid-bearing value carries an eenheid that does not equal the declared one. */
        WRONG_UNIT,
        /** An enumeratie value is not one of the domein's declared waarden. */
        UNKNOWN_ENUM_VALUE,
        /** A request name reads as two different declarations — in two vocabularies, or as a designation in one and as
         *  a canonical name — and the request has not pinned its input vocabulary. The service never guesses which. */
        AMBIGUOUS_NAME,
        /** A request or a descriptor entry point names a vocabulary the model does not declare. */
        UNKNOWN_VOCABULARY,
        /** A construct the service core deliberately does not support (dimensie/tijdlijn-valued input), or a detail of
         *  the session a core started without detail does not give ({@link CoreConfig#detail()}). */
        UNSUPPORTED,
        /** The entrypoint declares an invoer objecttype but the request supplies no instance of it. The invoer
         *  list is the declared contract; an absent required instance can never fire meaningfully (the scoped
         *  rules quantify over instances of that objecttype), so this is a hard 400 before any fire. */
        MISSING_INSTANCE,
        /** The entrypoint scopes a rule that CONSUMES a kenmerk/attribuut whose only PRODUCER (a
         *  kenmerktoekenning / gelijkstelling) sits OUTSIDE the scope, and the element is not a declared invoer.
         *  The scoped rule would read that element as a stale false/leeg — semantically diverging from the source
         *  concatenation the scenariotoetsen run against. A hard startup error unless the entrypoint opts out. */
        INCOMPLETE_SCOPE,
        /** The request JSON itself is malformed / structurally wrong. */
        MALFORMED
    }

    public static Violation of(Kind kind, String field, String message) {
        return new Violation(kind, field == null ? "" : field, message);
    }

    @Override public String toString() {
        return kind + (field.isEmpty() ? "" : " [" + field + "]") + ": " + message;
    }
}
