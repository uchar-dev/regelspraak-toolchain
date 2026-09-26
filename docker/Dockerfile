# Decision-service (beslisservice) image — multi-stage, built from the reactor.
#
# The build context is the repository root with the submodule uchar/ checked out (git submodule update --init
# --recursive) and without any .git: .dockerignore admits only what the two stages read. Stage 1 builds the one jar
# bin/regelspraak runs from, in one Maven run over the root pom: the parser comes from uchar's grammar through the
# antlr4-maven-plugin, so the build needs neither make nor curl. Stage 2 runs `bin/regelspraak serve` on that jar.

# ---- Stage 1: the jar ----------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Maven reads every module the root pom lists, so the Quarkus adapter's pom comes along; the image builds only the
# service and the modules it depends on (-pl service -am), not the adapter.
COPY pom.xml ./
COPY uchar-frontend/ uchar-frontend/
COPY toolchain/ toolchain/
COPY service/ service/
COPY service-quarkus/pom.xml service-quarkus/pom.xml
COPY uchar/frontends/regelspraak/ uchar/frontends/regelspraak/
COPY uchar/backends/drools/ uchar/backends/drools/

# No tests here: the reactor's suites run in CI and in `make poorten`, and the kind smoke replays the golden TOKA
# scenarios against the running service. Maven fetches the plugins and dependencies online inside this stage.
RUN mvn -B -ntp -DskipTests package -pl service -am

# ---- Stage 2: JDK runtime -------------------------------------------------------------------------------------
# JDK, not JRE: uchar's backend compiles the generated fact beans and the executable model in memory at startup,
# with the system Java compiler, which a JRE does not have.
FROM eclipse-temurin:21-jdk-jammy

# curl is only for the HEALTHCHECK below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /build/service/target/regelspraak.jar /app/regelspraak.jar
COPY bin/regelspraak /app/bin/regelspraak

# The served rule set: the TOKA rule files, the two model companions, and the self-contained marker source (used by
# the fail-fast / --allow-markers gate of the kind smoke).
COPY examples/toka/ /app/rules/toka/
COPY examples/toka-reconciliatie/casus-omissies.rgs /app/rules/companions/casus-omissies.rgs
COPY examples/toka-scenarios/scenario-model-extensie.rgs /app/rules/companions/scenario-model-extensie.rgs
COPY docker/marker/ /app/rules/marker/

# The kind smoke's rollout step rebuilds the image with another descriptor through this build argument; the file
# must lie in the build context (under docker/ or examples/, see .dockerignore). It lands on top of the TOKA
# descriptor copied above.
ARG DESCRIPTOR_SRC=examples/toka/toka.dienst.json
COPY ${DESCRIPTOR_SRC} /app/rules/toka/toka.dienst.json

COPY docker/entrypoint.sh /app/entrypoint.sh

# Bake the ordered TOKA source manifest at build time so the entrypoint gives the service its sources in exactly
# the order of TokaBron.bronBestanden() (service tests): the model foundation (toka-01..16) and the two companions,
# then the served rules (toka-17..47) WITHOUT the two files that do not resolve: toka-23 (datum+datum, §4.1 Tabel 2)
# and toka-45 (dimensionally inconsistent under uchar's unit-identity typing). Keep this filter in step with
# TokaBron.bronBestanden(): a source that does not resolve stops the service before it binds its port, so the
# HEALTHCHECK would never pass.
RUN chmod +x /app/entrypoint.sh /app/bin/regelspraak \
    && { ls /app/rules/toka/toka-0[1-9]-*.rgs /app/rules/toka/toka-1[0-6]-*.rgs | sort; \
         echo /app/rules/companions/casus-omissies.rgs; \
         echo /app/rules/companions/scenario-model-extensie.rgs; \
         ls /app/rules/toka/toka-1[7-9]-*.rgs /app/rules/toka/toka-[2-9][0-9]-*.rgs | sort | grep -v -e 'toka-23-' -e 'toka-45-'; \
       } > /app/rules/toka-manifest.txt

# Mounting a volume at /app/rules and overriding DESCRIPTOR + BRONNEN_MANIFEST serves an entirely different rule
# set from the same image. REGELSPRAAK_JAR points bin/regelspraak at the jar of stage 1; it never builds here.
ENV POORT=8080 \
    ALLOW_MARKERS=false \
    DESCRIPTOR=/app/rules/toka/toka.dienst.json \
    BRONNEN_MANIFEST=/app/rules/toka-manifest.txt \
    REGELSPRAAK_JAR=/app/regelspraak.jar

EXPOSE 8080

# The service builds every entry point BEFORE it binds the port, so /info answering means startup fully succeeded.
# Give it a long start-period for that build.
HEALTHCHECK --interval=10s --timeout=3s --start-period=180s --retries=3 \
    CMD curl -fsS http://127.0.0.1:${POORT}/info >/dev/null || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
