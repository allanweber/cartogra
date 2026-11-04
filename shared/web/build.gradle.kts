plugins {
    id("java-library")
    id("java-test-fixtures")
}

dependencies {
    api(project(":shared:common"))
    api("org.springframework:spring-web")
    api("org.springframework:spring-jdbc")
    api("org.springframework.security:spring-security-core")
    api("io.opentelemetry:opentelemetry-api")
    api("io.github.resilience4j:resilience4j-retry:2.3.0")
    api("org.slf4j:slf4j-api")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework.security:spring-security-web")
    // Only KafkaAuditEventAdapter needs it; services that use that adapter bring spring-kafka themselves.
    compileOnly("org.springframework.kafka:spring-kafka")

    testFixturesApi("org.springframework:spring-test")
    testFixturesApi("org.springframework:spring-webmvc")
    testFixturesApi("org.springframework.security:spring-security-web")
    testFixturesCompileOnly("jakarta.servlet:jakarta.servlet-api")

    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
