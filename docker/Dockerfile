# Decision-service (beslisservice) image — multi-stage.
#
# The card said `mvn -pl service -am package`, but this repo has NO aggregator pom
# (root pom.xml, engine/pom.xml, service/pom.xml are three standalone builds chained
# through the local repo), so -am is impossible. The equivalent is the sequential
# install chain below: install the parser, install the engine on top, then package
# the service + copy its runtime dependencies.

# ---- Stage 1: build the service jar + its runtime classpath -------------------
FROM maven:3.9-eclipse-temurin-21 AS build

# `make parser` (root pom generate-sources) shells out to make + curl (the Makefile
# downloads the ANTLR complete jar into lib/); the maven image lacks both.
RUN apt-get update \
    && apt-get install -y --no-install-recommends make curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy exactly what the three poms compile from (see .dockerignore for the inverse).
# vendor/ replaced grammar/ at the dependency inversion (9f7b001): the grammar AND the
# frontend sources both live in the vendored uchar slice now, and `make parser` plus the
# root pom's add-vendored-frontend source root read them from there. The stale
# `COPY grammar/ grammar/` pointed at a directory that no longer exists, so this stage
# could not build at all.
COPY Makefile pom.xml ./
COPY vendor/ vendor/
COPY tools/ tools/
COPY src/ src/
COPY engine/ engine/
COPY service/ service/

# Sequential install chain (no aggregator). Tests are skipped in the image build —
# the Drools-firing suites run in the kind-smoke gate against the live service.
RUN mvn -B -ntp -f pom.xml install -Dmaven.test.skip=true
RUN mvn -B -ntp -f engine/pom.xml install -Dmaven.test.skip=true
RUN mvn -B -ntp -f service/pom.xml package dependency:copy-dependencies \
        -Dmaven.test.skip=true -DincludeScope=runtime -DoutputDirectory=target/lib

# ---- Stage 2: lean JDK runtime -------------------------------------------------
# JDK, not JRE: BeslisserKern compiles the generated fact beans IN MEMORY at startup
# (BeanCompiler needs javax.tools' system Java compiler), so a JRE-only image fails
# with "geen JDK-compiler beschikbaar". This is the one deliberate deviation from
# the card's "minimal JRE 21".
FROM eclipse-temurin:21-jdk-jammy

# curl is only for the HEALTHCHECK below (the JRE image has no curl).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /build/service/target/regelspraak-service-2.3.0-SNAPSHOT.jar /app/service.jar
COPY --from=build /build/service/target/lib/ /app/lib/

# The served rule set: the TOKA rule files, the two model companions, and the
# self-contained marker source (used by the fail-fast / --allow-markers gate).
COPY examples/toka/ /app/rules/toka/
COPY examples/toka-reconciliatie/casus-omissies.rgs /app/rules/companions/casus-omissies.rgs
COPY examples/toka-scenarios/scenario-model-extensie.rgs /app/rules/companions/scenario-model-extensie.rgs
COPY docker/marker/ /app/rules/marker/

# H4 rollout rebuilds the image with a different descriptor via this build-arg; the
# overlay COPY lands on top of the TOKA descriptor already copied under rules/toka/.
ARG DESCRIPTOR_SRC=examples/toka/toka.dienst.json
COPY ${DESCRIPTOR_SRC} /app/rules/toka/toka.dienst.json

COPY docker/entrypoint.sh /app/entrypoint.sh

# Bake the ordered TOKA source manifest at build time so the entrypoint feeds
# DienstMain the bronbestanden in exactly TokaBron.bronBestanden() order: the
# model foundation (toka-01..16) + the two companions, then the served rules
# (toka-17..47) EXCLUDING the two files that do not resolve and therefore cannot be
# served: toka-23 (datum+datum, §4.1 Tabel 2) and toka-45 (dimensionally inconsistent
# under the eenheid-identiteit typing since besluit 16Y). Keep this filter in lock-step
# with TokaBron.bronBestanden(): a non-resolving source makes BeslisserKern.bouw fail,
# so the container would never bind its port and the HEALTHCHECK would never pass.
RUN chmod +x /app/entrypoint.sh \
    && { ls /app/rules/toka/toka-0[1-9]-*.rgs /app/rules/toka/toka-1[0-6]-*.rgs | sort; \
         echo /app/rules/companions/casus-omissies.rgs; \
         echo /app/rules/companions/scenario-model-extensie.rgs; \
         ls /app/rules/toka/toka-1[7-9]-*.rgs /app/rules/toka/toka-[2-9][0-9]-*.rgs | sort | grep -v -e 'toka-23-' -e 'toka-45-'; \
       } > /app/rules/toka-manifest.txt

# Mounting a volume at /app/rules and overriding DESCRIPTOR + BRONNEN_MANIFEST
# serves an entirely different rule set from the same image.
ENV POORT=8080 \
    ALLOW_MARKERS=false \
    DESCRIPTOR=/app/rules/toka/toka.dienst.json \
    BRONNEN_MANIFEST=/app/rules/toka-manifest.txt

EXPOSE 8080

# DienstMain compiles every scoped rule BEFORE binding the port, so /info answering
# means startup fully succeeded. Give it a long start-period for that compilation.
HEALTHCHECK --interval=10s --timeout=3s --start-period=180s --retries=3 \
    CMD curl -fsS http://127.0.0.1:${POORT}/info >/dev/null || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
