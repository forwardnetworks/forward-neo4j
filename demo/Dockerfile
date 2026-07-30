FROM gradle:9.2.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon clean test jar

FROM neo4j:2026.06.0
COPY --from=build /workspace/build/libs/forward-neo4j-plugin.jar /plugins/forward-neo4j-plugin.jar
