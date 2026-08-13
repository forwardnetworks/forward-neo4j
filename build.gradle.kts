plugins {
    java
    id("org.cyclonedx.bom") version "3.3.0"
}

group = "com.forwardnetworks.neo4j"
version = "0.3.0"

val neo4jVersion = "2026.07.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.neo4j:neo4j:$neo4jVersion")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.22.1")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    testImplementation("org.neo4j.test:neo4j-harness:$neo4jVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    archiveFileName = "forward-neo4j-plugin-${project.version}.jar"
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes(
            "Implementation-Title" to "Forward Neo4j Plugin",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.test {
    useJUnitPlatform()
}

// CycloneDX's aggregate task duplicates the root component when the connector depends on the plugin.
// Each release artifact has its own direct BOM instead.
tasks.named("cyclonedxBom") {
    enabled = false
    dependsOn("cyclonedxDirectBom")
}
