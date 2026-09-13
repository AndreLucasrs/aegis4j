rootProject.name = "aegis4j"

pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "aegis4j-api",
    "aegis4j-core",
    "aegis4j-guardrails-builtin",
    "aegis4j-skills",
    "aegis4j-provider-http-support",
    "aegis4j-provider-ollama",
    "aegis4j-provider-openai",
    "aegis4j-provider-anthropic",
    "aegis4j-testkit",
    "aegis4j-server",
    "aegis4j-mcp",
    "aegis4j-rag-jdbc-pgvector",
    "aegis4j-rag-mcp",
    "aegis4j-routing",
)
