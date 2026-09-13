plugins {
    id("dev.aegis4j.java-conventions")
    application
}

application {
    mainClass.set("dev.aegis4j.server.Aegis4jServerApp")
}

dependencies {
    implementation(project(":aegis4j-core"))
    implementation(project(":aegis4j-guardrails-builtin"))
    implementation(project(":aegis4j-skills"))
    implementation(project(":aegis4j-provider-ollama"))
    implementation(project(":aegis4j-provider-openai"))
    implementation(project(":aegis4j-provider-anthropic"))
    implementation(project(":aegis4j-mcp"))
    implementation(project(":aegis4j-rag-jdbc-pgvector"))
    implementation(project(":aegis4j-rag-mcp"))
    implementation(project(":aegis4j-routing"))
    implementation(libs.javalin)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
    implementation(libs.slf4j.simple)
    implementation(libs.postgresql.driver)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.wiremock)
    testRuntimeOnly(libs.junit.platform.launcher)
}
