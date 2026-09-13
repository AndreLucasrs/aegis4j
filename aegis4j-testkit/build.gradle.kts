plugins {
    id("dev.aegis4j.java-conventions")
}

dependencies {
    api(project(":aegis4j-api"))
    api(libs.wiremock)
    api(libs.junit.jupiter)
    api(libs.assertj.core)
}
