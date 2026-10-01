FROM gradle:9.6.1-jdk21@sha256:9795f8c9009a7de6adea522b907c9aadc38fe7c313874112c2b696c8e70b0303 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon --max-workers=1 clean :test jar

FROM neo4j:2026.09.0@sha256:91fb0bf237c41b7b3dcbe84703aa0b82e0d7d067b16e1c8ab21f03fc679edf4e
LABEL org.opencontainers.image.title="Forward Neo4j reference integration" \
      org.opencontainers.image.source="https://github.com/forwardnetworks/forward-neo4j" \
      org.opencontainers.image.licenses="MIT"
COPY --from=build /workspace/build/libs/forward-neo4j-plugin-*.jar /plugins/forward-neo4j-plugin.jar
