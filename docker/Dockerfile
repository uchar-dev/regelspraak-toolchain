# Minimal sandbox for running Claude Code on the RegelSpraak Java build.
#
# Ships a JDK 21 (java/javac), make, git, and Node + Claude Code. The ANTLR jar
# rides in with the bind-mounted repo (lib/), so the build needs no Maven and no
# network. Launched by run-sandbox.sh in the project root, which mounts only this
# repo and persists the login in a named volume. See ../CLAUDE.md "Working autonomously".
#
# JDK 21 (below the host's newest JDK) is deliberate: Drools 10.x and Quarkus
# 3.35 require Java >= 17 and are validated against 21 LTS, so the parser builds
# on the same runtime as the Drools engine it eventually feeds.
FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends make git ca-certificates curl gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install -g @anthropic-ai/claude-code \
    && apt-get purge -y gnupg && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

# --dangerously-skip-permissions refuses to run as root, so run as a normal user.
# Claude Code writes its login token (~/.claude/.credentials.json) and onboarding
# state (~/.claude.json) under this user's HOME, which run-sandbox.sh persists in
# a named volume so you authenticate only once.
RUN useradd -m -s /bin/bash claude
USER claude
WORKDIR /work

CMD ["claude", "--dangerously-skip-permissions"]
