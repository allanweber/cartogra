plugins { id("java-library") }

dependencies {
    api(project(":shared:common"))
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers-kafka")
    api("org.testcontainers:testcontainers-junit-jupiter")
    api("org.postgresql:postgresql")
    api("org.junit.jupiter:junit-jupiter-api")
    runtimeOnly("org.junit.jupiter:junit-jupiter-engine")
}
