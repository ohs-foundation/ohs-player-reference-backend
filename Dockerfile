# Plugin image, published to ghcr.io/ohs-foundation/ohs-player-gateway.
#
# The FROM tag must match the com.google.fhir.gateway:server version in pom.xml,
# because the plugin was compiled against that gateway and the base image runs it.
# Bump both in the same commit.
#
# The jar is built by CI before this runs rather than inside the image, so
# `mvn verify -Perror-prone` in ci.yml stays the single definition of a passing
# build. The base image loads everything under /app/plugins.
#
# The COPY glob takes every jar it matches, so target/ has to hold only one. CI
# builds from a fresh checkout, and a local build wants `mvn clean package` after
# a version bump so a stale jar cannot ride along.
FROM ghcr.io/ohs-foundation/fhir-gateway:0.5.0

COPY target/ohs-player-backend-extensions-*.jar /app/plugins/
