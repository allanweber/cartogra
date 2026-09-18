plugins {
    id("java-library")
}

dependencies {
    api(project(":shared:common"))
    api("org.springframework:spring-web")
    api("org.springframework.security:spring-security-core")
    api("io.opentelemetry:opentelemetry-api")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
