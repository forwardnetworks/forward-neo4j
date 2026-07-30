plugins {
    java
}

group = "com.forwardnetworks.neo4j"
version = "0.2.0"

val neo4jVersion = "2026.06.0"

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
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.20.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    testImplementation("org.neo4j.test:neo4j-harness:$neo4jVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    archiveFileName = "forward-neo4j-plugin-${project.version}.jar"
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
