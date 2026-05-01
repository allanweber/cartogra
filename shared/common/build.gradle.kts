plugins {
    id("java-library")
}

dependencies {
    // Jackson 3 — group ID changed from com.fasterxml.jackson to tools.jackson in SB4
    api("tools.jackson.core:jackson-databind")
}