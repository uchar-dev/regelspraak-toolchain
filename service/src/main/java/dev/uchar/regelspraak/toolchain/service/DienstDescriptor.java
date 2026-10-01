package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * The declarative service descriptor — a JSON sidecar {@code <naam>.dienst.json} that sits next to the
 * RegelSpraak {@code .rgs} source and turns a resolved rule model into a decision service: which entry
 * points to expose, which rules each scopes, which objecttypes it takes in and which
 * attribuut/kenmerk values it returns, plus the parameter defaults. It carries NO RegelSpraak
 * meaning of its own — every name in it is validated against the resolved model by
 * {@link DescriptorLoader}, and an unknown regel / regelgroep / objecttype / attribuut / kenmerk /
 * parameter is a startup error naming the offending value.
 *
 * <p>This is a pure data shape (Jackson-bound records); the framework-agnostic core
 * ({@link BeslisserKern}) consumes it and the HTTP layer (fase 3) is a thin adapter on top.
 *
 * @param dienst        the service identity (name + version)
 * @param entrypoints   the exposed entry points, in declared order
 * @param parameters    service-wide parameter defaults (name → JSON value); a request may override each
 * @param parametersets OPTIONAL (fase 5, card {@code 20260705-15:18-geldigheid-parametersets-peildatum.md}):
 *                      the file name of the validity-versioned parameterset sidecar (a
 *                      {@code <naam>.parameters.json} in the {@link dev.uchar.regelspraak.toolchain.engine.ParametersetLader}
 *                      shape), resolved RELATIVE to this descriptor file. When present, {@link BeslisserKern}
 *                      loads it against the resolved model at startup and resolves each parameter's VINTAGE per
 *                      request against the one date authority (the peildatum, which IS the rekendatum global).
 *                      Absent = no sidecar (the degenerate single-value / descriptor-default path, byte-identical
 *                      to fase 1-4). <b>Conflict rule (pinned):</b> a parameter named in BOTH {@code parameters}
 *                      (defaults) AND the sets is a startup error — ambiguous authority, {@link BeslisserKern}
 *                      refuses start naming the parameter.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DienstDescriptor(
        Dienst dienst,
        List<Entrypoint> entrypoints,
        Map<String, JsonNode> parameters,
        String parametersets) {

    /** The service identity block. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dienst(String naam, String versie) {}

    /**
     * One entry point: a name, its URL path (fase 3), the rule scope it fires, the objecttypen it accepts
     * as input, and the {@code Objecttype.attribuut} / {@code Objecttype:kenmerk} outputs it returns.
     *
     * @param naam    the entry point name (human-readable)
     * @param pad     the URL path segment (kebab-case, no leading slash)
     * @param scope   which rules/regelgroepen this entry point fires
     * @param invoer  the objecttype names accepted as request instances
     * @param uitvoer the output selectors: {@code "Objecttype.attribuut"} or {@code "Objecttype:kenmerk"}
     * @param invoerElementen OPTIONAL element-level input declarations, in the {@code uitvoer} selector syntax
     *        ({@code "Objecttype.attribuut"} / {@code "Objecttype:kenmerk"}): each names a kenmerk/attribuut the
     *        CALLER supplies in the request rather than one the scope derives. The scope-completeness check
     *        ({@link BeslisserKern#bouw}) skips a consumed element declared here — its missing in-scope producer
     *        is intentional (an ordinary {@code invoer} objecttype only declares instance presence, NOT that its
     *        derived kenmerk/attribuut values are caller-supplied, so the opt-out is element-granular).
     * @param scopeControleUit OPT-OUT of the whole startup scope-completeness validation for this entry point.
     *        The check verifies that every kenmerk/attribuut the scoped rules and defaults groups READ has an
     *        in-scope producer (or an {@code invoerElementen} declaration); a producer that exists but sits OUTSIDE
     *        the scope makes the scoped form read a stale-false/leeg value, semantically diverging from the source
     *        concatenation the scenariotoetsen run against. {@code true} waives the check entirely; absent/false =
     *        strict.
     * @param vocabulaire OPTIONAL the vocabulary an answer of this entry point names the declarations in when the
     *        request chooses none: one the model declares, or {@code canoniek}. Absent = the canonical names. The
     *        descriptor's own names (scope, invoer, uitvoer, invoerElementen, parameters) are canonical names.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entrypoint(
            String naam,
            String pad,
            Scope scope,
            List<String> invoer,
            List<String> uitvoer,
            @JsonProperty("invoerElementen") List<String> invoerElementen,
            @JsonProperty("scopeControleUit") boolean scopeControleUit,
            @JsonProperty("vocabulaire") String vocabulaire) {

        public List<String> invoerElementenOfLeeg() { return invoerElementen == null ? List.of() : invoerElementen; }
    }

    /**
     * The rule scope of an entry point: named rules ({@code regelnamen}) and/or named regelgroepen
     * ({@code regelgroepen}). At least one must be non-empty; the loader validates every name against the
     * model and refuses an unknown reference.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scope(
            @JsonProperty("regelnamen") List<String> regelnamen,
            @JsonProperty("regelgroepen") List<String> regelgroepen) {

        public List<String> regelnamenOfLeeg() { return regelnamen == null ? List.of() : regelnamen; }
        public List<String> regelgroepenOfLeeg() { return regelgroepen == null ? List.of() : regelgroepen; }
    }
}
