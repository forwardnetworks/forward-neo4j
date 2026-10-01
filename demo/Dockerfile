FROM gradle:9.8.0-jdk21@sha256:3bad8c6f194befc7a1a9d4a6452aed83bdbb5a6d6f6752e5b0acbfeec52961b9 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon --max-workers=1 clean :test jar

FROM neo4j:2026.06.0@sha256:ba2b859bdbe7017a9baa1a7b5681ac9732198753719b0a502e3645feddfdec72
LABEL org.opencontainers.image.title="Forward Neo4j reference integration" \
      org.opencontainers.image.source="https://github.com/forwardnetworks/forward-neo4j" \
      org.opencontainers.image.licenses="MIT"
COPY --from=build /workspace/build/libs/forward-neo4j-plugin-*.jar /plugins/forward-neo4j-plugin.jar
