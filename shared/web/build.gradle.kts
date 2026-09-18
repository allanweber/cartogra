plugins {
    id("java-library")
}

dependencies {
    api(project(":shared:common"))
    api("org.springframework:spring-web")
    api("io.opentelemetry:opentelemetry-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
