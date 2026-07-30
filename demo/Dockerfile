FROM gradle:9.2.0-jdk21@sha256:f73b9e12248ea459a1118f40da095c5305e90e2ebb22b790bcf0bfb9fcb1e39b AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon clean test jar

FROM neo4j:2026.06.0@sha256:ba2b859bdbe7017a9baa1a7b5681ac9732198753719b0a502e3645feddfdec72
LABEL org.opencontainers.image.title="Forward Neo4j reference integration" \
      org.opencontainers.image.source="https://github.com/forwardnetworks/forward-neo4j" \
      org.opencontainers.image.licenses="MIT"
COPY --from=build /workspace/build/libs/forward-neo4j-plugin-*.jar /plugins/forward-neo4j-plugin.jar
