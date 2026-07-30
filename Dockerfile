# syntax=docker/dockerfile:1

# Builds a self-contained image running the FHIR Gateway with the OHS Player backend
# extensions injected onto its classpath via -Dloader.path.
#
# The gateway's runnable jar (com.google.fhir.gateway:exec) is not published to Maven
# Central or Docker Hub, so it is built from source in the first stage. This project's
# extensions jar only needs com.google.fhir.gateway:server, which is on Central.

# ---- Stage 1: build the FHIR Gateway exec jar -------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS gateway-build

ARG GATEWAY_REPO=https://github.com/ohs-foundation/fhir-gateway.git
ARG GATEWAY_REF=main

RUN apt-get update \
 && apt-get install -y --no-install-recommends git \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /build
# fetch-by-ref rather than `clone --branch`, which rejects commit SHAs. GATEWAY_REF may be a
# branch, a tag or a full 40-character SHA; abbreviated SHAs are not resolvable over the wire.
# Both ARGs are part of this layer's cache key, so bumping either one re-fetches — a moving
# branch name does not, which is why a SHA or a fresh tag is the way to pin a specific commit.
RUN git init -q . \
 && git remote add origin "${GATEWAY_REPO}" \
 && git fetch --depth 1 origin "${GATEWAY_REF}" \
 && git checkout -q FETCH_HEAD

# spotless:apply is bound to the compile phase upstream and its markdown formatter needs
# prettier/node; skipping it keeps this stage free of a Node toolchain. Formatting and
# license headers are enforced in the gateway's own CI, not here.
RUN mvn -B -DskipTests -Dlicense.skip=true -Dspotless.apply.skip=true package

# ---- Stage 2: build the OHS Player extensions jar (requires JDK 21) ---------------
FROM maven:3.9-eclipse-temurin-21 AS plugin-build

WORKDIR /build
COPY pom.xml lombok.config ./
# Warm the dependency cache in its own layer so source edits do not re-download.
# Best-effort: anything it misses is fetched by the package step below.
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
# spotless:check is bound to the validate phase; it is enforced in CI.
RUN mvn -B -DskipTests -Dspotless.check.skip=true package

# ---- Stage 3: runtime ------------------------------------------------------------
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="OHS Player Reference Backend" \
      org.opencontainers.image.description="FHIR Gateway with the OHS Player backend extensions loaded" \
      org.opencontainers.image.source="https://github.com/ohs-foundation/ohs-player-reference-backend" \
      org.opencontainers.image.licenses="Apache-2.0"

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --uid 10001 app

WORKDIR /app

COPY --from=gateway-build /build/exec/target/fhir-gateway-exec.jar /app/fhir-gateway-exec.jar
COPY --from=gateway-build /build/resources/hapi_page_url_allowed_queries.json /app/resources/
# The shade plugin also emits original-*.jar; copy only the shaded artifact.
COPY --from=plugin-build /build/target/ohs-player-backend-extensions-1.0-SNAPSHOT.jar \
                         /app/plugins/ohs-player-backend-extensions.jar

# Gateway knobs (see the upstream FHIR Gateway documentation).
ENV PROXY_PORT=8080 \
    BACKEND_TYPE=HAPI \
    ACCESS_CHECKER=list \
    RUN_MODE=PROD

# OHS Player extension knobs.
ENV IAM_PROVIDER=keycloak \
    BULK_IMPORT_BATCH_SIZE=50

ENV JAVA_OPTS=-XX:MaxRAMPercentage=75 \
    LOADER_PATH_JAR=/app/plugins/ohs-player-backend-extensions.jar

# PROXY_TO, TOKEN_ISSUER, IAM_PROVIDER_CLIENT_ID and IAM_PROVIDER_CLIENT_SECRET are
# intentionally left unset. Startup fails with an explicit message when they are missing,
# which is clearer than a placeholder default failing to connect.

EXPOSE 8080

USER app

# /fhir/metadata answers 401 without a token, so --fail is deliberately omitted: any HTTP
# response means the server is up and serving.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -s -o /dev/null "http://localhost:${PROXY_PORT}/fhir/metadata" || exit 1

# sh -c expands $JAVA_OPTS; exec keeps java as PID 1 so SIGTERM shuts it down cleanly.
ENTRYPOINT ["/bin/sh", "-c", \
  "exec java $JAVA_OPTS -Dloader.path=\"$LOADER_PATH_JAR\" -jar /app/fhir-gateway-exec.jar --server.port=${PROXY_PORT}"]
