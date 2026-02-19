plugins {
    kotlin("jvm") version "2.1.10"
}

group = "io.effectivelabs"
version = "0.0.1"

repositories {
    mavenCentral()
}

val exposedVersion = "0.58.0"
val testcontainersVersion = "1.20.4"

dependencies {
    // Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    // PostgreSQL JDBC driver
    implementation("org.postgresql:postgresql:42.7.4")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")

    // SLF4J for Exposed/Testcontainers logging
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
