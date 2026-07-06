package io.github.yhavinga.regelspraak.quarkus;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness probe {@code alive}: always UP while the process runs. SmallRye serves this at {@code /q/health/live}.
 */
@Liveness
@ApplicationScoped
public class AliveCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("alive");
    }
}
