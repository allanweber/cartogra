plugins {
    id("java-library")
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

    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
