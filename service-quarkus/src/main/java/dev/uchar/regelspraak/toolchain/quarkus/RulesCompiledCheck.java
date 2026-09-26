package dev.uchar.regelspraak.toolchain.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.uchar.regelspraak.toolchain.service.BeslisserKern;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe {@code rules-compiled}: UP once the {@link BeslisserKern} producer succeeded (uchar built every
 * entrypoint's {@code KieBase} at startup). If the producer threw (e.g. a marker rule in scope under strict
 * {@code ALLOW_MARKERS=false}), CDI construction fails and the app never finishes starting — so readiness stays
 * DOWN. SmallRye serves this at {@code /q/health/ready}.
 */
@Readiness
@ApplicationScoped
public class RulesCompiledCheck implements HealthCheck {

    @Inject
    BeslisserKern kern;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("rules-compiled")
                .status(kern != null && !kern.entrypoints().isEmpty())
                .withData("entrypoints", kern == null ? 0 : kern.entrypoints().size())
                .build();
    }
}
