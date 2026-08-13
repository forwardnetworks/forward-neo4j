plugins {
    application
    id("org.cyclonedx.bom") version "3.4.0"
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    implementation("org.neo4j.driver:neo4j-java-driver:6.1.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.neo4j.test:neo4j-harness:2026.06.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.forwardnetworks.neo4j.ForwardAuraConnector"
    applicationName = "forward-neo4j-connector"
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    useJUnitPlatform()
    mustRunAfter(rootProject.tasks.named("test"))
}

tasks.named("cyclonedxBom") {
    enabled = false
    dependsOn("cyclonedxDirectBom")
}
