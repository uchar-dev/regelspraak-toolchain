package dev.uchar.regelspraak.toolchain.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe {@code rules-compiled}: UP once the {@link DecisionCore} producer succeeded (uchar built every
 * entrypoint's {@code KieBase} at startup). If the producer threw (e.g. a marker rule in scope under strict
 * {@code ALLOW_MARKERS=false}), CDI construction fails and the app never finishes starting — so readiness stays
 * DOWN. SmallRye serves this at {@code /q/health/ready}.
 */
@Readiness
@ApplicationScoped
public class RulesCompiledCheck implements HealthCheck {

    @Inject
    DecisionCore core;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("rules-compiled")
                .status(core != null && !core.entrypoints().isEmpty())
                .withData("entrypoints", core == null ? 0 : core.entrypoints().size())
                .build();
    }
}
