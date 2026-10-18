package dev.uchar.regelspraak.toolchain.quarkus;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Q2 PROBES. For the healthy TOKA service (strict, clean-compiling):
 * <ul>
 *   <li>{@code GET /q/health/ready} → 200, overall status UP, and the {@code rules-compiled} check UP;</li>
 *   <li>{@code GET /q/health/live}  → 200, overall status UP.</li>
 * </ul>
 *
 * <p><b>Readiness-DOWN-on-marker (nice-to-have, described not faked).</b> A marker rule in scope under strict
 * ({@code ALLOW_MARKERS=false}) makes {@link CoreProducer#decisionCore()} throw {@code ServiceError}; CDI
 * construction fails and the app never finishes starting, so {@code /q/health/ready} would be DOWN (or the app
 * would refuse to boot). Arranging that inside a {@code @QuarkusTest} needs a SECOND app configuration (a
 * marker-scoping descriptor) — a distinct boot, not expressible in this single healthy boot — so it is documented
 * here and left to the CLI's exit-3 discipline ({@code ServiceMainTest} / {@code MarkerFailFastTest}) rather than
 * faked green.
 */
@QuarkusTest
@TestProfile(TokaTestProfile.class)
class HealthTest {

    @Test
    void readiness_is_up_and_rules_compiled() {
        when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", is("UP"))
                .body("checks.find { it.name == 'rules-compiled' }.status", equalTo("UP"));
    }

    @Test
    void liveness_is_up() {
        when().get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", is("UP"));
    }
}
