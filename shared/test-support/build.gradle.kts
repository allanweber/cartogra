plugins { id("java-library") }

dependencies {
    api(project(":shared:common"))
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers-kafka")
    api("org.testcontainers:testcontainers-junit-jupiter")
    api("org.postgresql:postgresql")
    api("org.junit.jupiter:junit-jupiter-api")
    api("org.assertj:assertj-core:3.26.3")
    api("com.atlassian.oai:swagger-request-validator-core:2.46.0")
    runtimeOnly("org.junit.jupiter:junit-jupiter-engine")
}
