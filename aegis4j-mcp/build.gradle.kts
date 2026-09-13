plugins {
    id("dev.aegis4j.java-conventions")
}

dependencies {
    api(project(":aegis4j-api"))
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.wiremock)
    testRuntimeOnly(libs.junit.platform.launcher)
}
