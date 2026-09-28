plugins {
    id("dev.aegis4j.java-conventions")
}

dependencies {
    api(project(":aegis4j-api"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(project(":aegis4j-testkit"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
