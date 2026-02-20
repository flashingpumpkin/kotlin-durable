plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

val springBootVersion = "3.4.3"

dependencies {
    api(project(":"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")
    implementation("org.springframework.boot:spring-boot-starter-jdbc:$springBootVersion")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.postgresql:postgresql:42.7.4")
}

tasks.test {
    useJUnitPlatform()
}
