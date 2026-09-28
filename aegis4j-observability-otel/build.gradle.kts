plugins {
    id("dev.aegis4j.java-conventions")
}

dependencies {
    api(project(":aegis4j-core"))
    implementation(libs.opentelemetry.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(project(":aegis4j-testkit"))
    testImplementation(libs.opentelemetry.sdk.testing)
    testRuntimeOnly(libs.junit.platform.launcher)
}
