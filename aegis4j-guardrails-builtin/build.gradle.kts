plugins {
    id("dev.aegis4j.java-conventions")
}

dependencies {
    api(project(":aegis4j-api"))

    // Isolated to this module: JSON schema validation is only needed by
    // JsonSchemaOutputGuard, core stays free of the Jackson/schema stack.
    implementation(libs.jackson.databind)
    implementation(libs.json.schema.validator)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(project(":aegis4j-testkit"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
