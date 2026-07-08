package io.github.yhavinga.regelspraak.quarkus;

import io.quarkus.runtime.annotations.RegisterForReflection;

import io.github.yhavinga.regelspraak.service.DienstDescriptor;

/**
 * Native-image reflection registration for the descriptor DTOs. {@link DienstDescriptor} and its nested records
 * live in the framework-agnostic {@code regelspraak-service} library, so Quarkus's Jackson processor does not see
 * them at build time and GraalVM strips their reflection metadata — {@code DescriptorLoader}'s
 * {@code MAPPER.readValue(json, DienstDescriptor.class)} then fails at native runtime with
 * "cannot deserialize ... this appears to be a native image". Registering the record classes here restores the
 * canonical-constructor + record-component reflection Jackson binds against. JVM mode is unaffected (the
 * annotation is a native-build hint only). The parameterset sidecar path needs NO entry here — its loader
 * ({@code ParametersetLader}) reads a JsonNode tree, not a POJO bind.
 */
@RegisterForReflection(targets = {
        DienstDescriptor.class,
        DienstDescriptor.Dienst.class,
        DienstDescriptor.Entrypoint.class,
        DienstDescriptor.Scope.class,
})
public final class NativeReflectie {
    private NativeReflectie() {}
}
