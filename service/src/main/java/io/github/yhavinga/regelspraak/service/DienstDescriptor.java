package io.github.yhavinga.regelspraak.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * The declarative service descriptor — a JSON sidecar {@code <naam>.dienst.json} that sits next to the
 * RegelSpraak {@code .rs} source and turns a resolved rule model into a decision service: which entry
 * points to expose, which rules each scopes, which objecttypes it takes in and which
 * attribuut/kenmerk values it returns, plus the parameter defaults. It carries NO RegelSpraak
 * meaning of its own — every name in it is validated against the resolved model by
 * {@link DescriptorLoader}, and an unknown regel / regelgroep / objecttype / attribuut / kenmerk /
 * parameter is a startup error naming the offending value.
 *
 * <p>This is a pure data shape (Jackson-bound records); the framework-agnostic core
 * ({@link BeslisserKern}) consumes it and the HTTP layer (fase 3) is a thin adapter on top.
 *
 * @param dienst      the service identity (name + version)
 * @param entrypoints the exposed entry points, in declared order
 * @param parameters  service-wide parameter defaults (name → JSON value); a request may override each
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DienstDescriptor(
        Dienst dienst,
        List<Entrypoint> entrypoints,
        Map<String, JsonNode> parameters) {

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
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entrypoint(
            String naam,
            String pad,
            Scope scope,
            List<String> invoer,
            List<String> uitvoer) {}

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
