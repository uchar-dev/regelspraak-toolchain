package io.github.yhavinga.regelspraak.service;

/**
 * A typed validation violation (a <i>schending</i>) — the single currency of every fail-fast check in the
 * service core. It carries the KIND of problem, a machine-usable FIELD PATH naming exactly what was wrong
 * ({@code "instanties[0].attributen.leeftijd"}, {@code "entrypoints[2].scope.regelnamen[0]"},
 * {@code "parameters.volwassenleeftijd"}), and a human-readable message. Fase 3 turns a request-mapping
 * violation into a 400 whose body names {@link #veld()}, and a startup (descriptor/marker) violation into a
 * refused start naming the offending value.
 *
 * @param soort   the category of violation
 * @param veld    the field path that located the problem (dot/bracket notation), never null (may be "")
 * @param bericht the human diagnostic, always naming the offending value
 */
public record Schending(Soort soort, String veld, String bericht) {

    public enum Soort {
        /** A descriptor references a regel/regelgroep/objecttype/attribuut/kenmerk/parameter the model lacks. */
        ONBEKENDE_VERWIJZING,
        /** An in-scope construct is a marker (not lowered / overgeslagen) and markers are not allowed. */
        MARKER,
        /** A request field names an unknown objecttype/attribuut/kenmerk/parameter/rol. */
        ONBEKEND_VELD,
        /** A request value has the wrong datatype (a string where a number is due, a bad date, …). */
        VERKEERD_TYPE,
        /** An eenheid-bearing value carries an eenheid that does not equal the declared one. */
        VERKEERDE_EENHEID,
        /** An enumeratie value is not one of the domein's declared waarden. */
        ONBEKENDE_ENUM_WAARDE,
        /** A construct the service core deliberately does not support (dimensie/tijdlijn-valued input). */
        NIET_ONDERSTEUND,
        /** The entrypoint declares an invoer objecttype but the request supplies no instance of it. The invoer
         *  list is the declared contract; an absent required instance can never fire meaningfully (the scoped
         *  rules quantify over instances of that objecttype), so this is a hard 400 before any fire. */
        ONTBREKENDE_INSTANTIE,
        /** The request JSON itself is malformed / structurally wrong. */
        MALFORMED
    }

    public static Schending van(Soort soort, String veld, String bericht) {
        return new Schending(soort, veld == null ? "" : veld, bericht);
    }

    @Override public String toString() {
        return soort + (veld.isEmpty() ? "" : " [" + veld + "]") + ": " + bericht;
    }
}
