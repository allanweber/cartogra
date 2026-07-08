plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    args("--spring.profiles.active=dev")
}

tasks.named<ProcessResources>("processTestResources") {
    from(rootProject.file("docs/api/gateway-errors.openapi.yaml"))
}

dependencies {
    implementation(project(":shared:common"))
    implementation("org.springframework.cloud:spring-cloud-gateway-server-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.20.0-alpha")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Auth + DB (connects to same PostgreSQL schema as registry — no Flyway)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    runtimeOnly("org.postgresql:postgresql")

    // Rate limiting (blocking Redis)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    testImplementation(project(":shared:test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
    testImplementation("org.wiremock:wiremock-standalone:3.9.1")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
}
