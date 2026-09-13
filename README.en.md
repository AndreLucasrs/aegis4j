# Aegis4J

[🇧🇷 Português](README.md) | 🇬🇧 English | [🇪🇸 Español](README.es.md)

A library for the JVM ecosystem (Java, Kotlin, Clojure, Scala) that sits in
front of LLMs, combining:

- **Provider abstraction** — swap LLM providers at runtime (Ollama, and
  Anthropic/OpenAI in the future) behind one common `Provider` interface.
- **Deterministic guardrails** — rules that run in plain code (regex, length
  limits, etc.), never another LLM call, applied to both input and output.
- **Skills with progressive disclosure** — only name+description enter the
  prompt by default; a skill's full body only loads when it's activated.
- **Dual distribution** — usable as an embedded library (Gradle/Maven
  dependency) or as an HTTP sidecar (OpenAI-compatible request/response
  shape), so any language/CLI outside the JVM can use it too.
- **MCP client** (stdio + HTTP/SSE) — connects to any MCP (Model Context
  Protocol) server to expose external tools/resources.
- **Extensible RAG/retrieval** — a single `Retriever` interface, with
  implementations both via direct pgvector (JDBC) and via any MCP server as
  the search source.
- **Rule-based model routing** — programmatic (Java) or declarative (YAML)
  rules, whichever the library's user defines; first matching rule wins.

Current status: **v0.2** — see [Scope and limitations](#scope-and-limitations-v02)
at the bottom.

## Requirements

- Java 17+ (the build toolchain targets Java 17 — any JVM 17 or newer runs
  the library; it does not work on Java 8/11)
- [Ollama](https://ollama.com) running locally (`http://localhost:11434`) if
  you want real responses instead of fake-backed tests.

No extra installation needed — the Gradle wrapper (`./gradlew`) is already in
the repository.

## Build and tests

```bash
./gradlew build
```

Compiles every module and runs the full test suite (guards, skills, the
Ollama provider against WireMock, and the server's acceptance test) — none of
it touches real network.

## Running the HTTP sidecar

```bash
./gradlew :aegis4j-server:run
```

Environment variables (all optional):

| Variable                     | Default                  | Description                                               |
|-------------------------------|---------------------------|----------------------------------------------------------|
| `AEGIS4J_PORT`                | `8686`                    | Sidecar's HTTP port                                       |
| `AEGIS4J_PROVIDER_ID`         | `ollama`                  | Active provider                                            |
| `AEGIS4J_OLLAMA_BASE_URL`     | `http://localhost:11434`  | Ollama base URL                                            |
| `AEGIS4J_MAX_INPUT_CHARS`     | `4000`                    | Input character ceiling (`MaxLengthGuard`)                 |
| `AEGIS4J_SKILLS_DIR`          | *(none)*                  | Directory with declarative skills (`.md` + frontmatter)    |
| `AEGIS4J_RETRIEVER`           | *(none)*                  | `mcp` — enables retrieval via MCP (`pgvector` throws an explicit error in v0.2, see [limitations](#scope-and-limitations-v02)) |
| `AEGIS4J_MCP_RETRIEVER_TRANSPORT` | *(none)*              | `stdio` or `http`                                          |
| `AEGIS4J_MCP_RETRIEVER_COMMAND`   | *(none)*              | MCP server command, if transport is `stdio`                |
| `AEGIS4J_MCP_RETRIEVER_URL`       | *(none)*              | MCP server URL, if transport is `http`                     |
| `AEGIS4J_MCP_RETRIEVER_TOOL`      | `search`              | MCP tool name used for search                               |
| `AEGIS4J_ROUTING_CONFIG`      | *(none)*                  | Path to a `routing.yaml` — enables per-request `model` routing |

Example with the sample skill (`aegis4j-server/src/main/resources/skills`)
loaded:

```bash
AEGIS4J_SKILLS_DIR="$PWD/aegis4j-server/src/main/resources/skills" \
  ./gradlew :aegis4j-server:run
```

Health check:

```bash
curl http://localhost:8686/health
```

### Chat completions (OpenAI-compatible shape)

```bash
curl -X POST http://localhost:8686/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
        "model": "qwen2.5-coder:7b",
        "messages": [
          {"role": "user", "content": "what is the weather like today?"}
        ]
      }'
```

Response:

```json
{
  "id": "ollama-...",
  "object": "chat.completion",
  "created": 1234567890,
  "model": "qwen2.5-coder:7b",
  "choices": [
    {"index": 0, "message": {"role": "assistant", "content": "..."}, "finish_reason": "stop"}
  ],
  "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
}
```

If the input or output contains an email, an API key, a valid credit card
(Luhn-checked) or an IPv4 address, `RegexPiiGuard` redacts it automatically
before continuing through the pipeline. An input longer than
`AEGIS4J_MAX_INPUT_CHARS` is blocked with HTTP 400.

## Using it as an embedded library (Java)

```java
ProviderRegistry providerRegistry = new ProviderRegistry();
providerRegistry.register(OllamaProvider.create());

SkillRegistry skillRegistry = SkillRegistry.inMemory();
new MarkdownSkillLoader().loadDirectory(Path.of("skills")).forEach(skillRegistry::register);

Aegis4jEngine engine = Aegis4jEngine.builder()
        .providerRegistry(providerRegistry)
        .guardChain(GuardChain.of(
                MaxLengthGuard.forInput(4000),
                RegexPiiGuard.allPatterns()
        ))
        .skillRegistry(skillRegistry)
        .build();

CompletionResponse response = engine.chat(ChatRequest.builder()
        .providerId(OllamaProvider.ID)
        .model("qwen2.5-coder:7b")
        .userInput("what is the weather like today?")
        .build());

System.out.println(response.content());
```

### Kotlin / Clojure / Scala

Since the core is pure Java, consuming it is plain JVM interop:

```kotlin
val engine = Aegis4jEngine.builder()
    .provider(OllamaProvider.create())
    .build()

val response = engine.chat(
    ChatRequest.builder()
        .providerId(OllamaProvider.ID)
        .model("qwen2.5-coder:7b")
        .userInput("what is the weather like today?")
        .build()
)
println(response.content())
```

```clojure
(import '[dev.aegis4j.core.engine Aegis4jEngine ChatRequest]
        '[dev.aegis4j.provider.ollama OllamaProvider])

(def engine (-> (Aegis4jEngine/builder)
                (.provider (OllamaProvider/create))
                (.build)))

(def request (-> (ChatRequest/builder)
                  (.providerId OllamaProvider/ID)
                  (.model "qwen2.5-coder:7b")
                  (.userInput "what is the weather like today?")
                  (.build)))

(println (.content (.chat engine request)))
```

## Creating a declarative skill

Create a `.md` file with a YAML frontmatter block:

```markdown
---
name: weather-explainer
description: Explains weather concepts in simple, non-technical terms.
triggers: [weather, forecast, clima]
---
You are an expert meteorologist. Explain weather phenomena in plain language...
```

The name/description/triggers always enter the system prompt's catalog; the
body is only read from disk and injected when one of the `triggers` appears
in the user's input.

## RAG/retrieval

```java
// direct pgvector (JDBC) — the Embedder must be supplied by the library's user (see limitations)
Retriever pgvectorRetriever = new PgVectorRetriever(dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"));

// or via any MCP server with a search tool
McpClient mcpClient = new McpClient(new StdioMcpTransport(List.of("my-mcp-server"), Map.of(), Duration.ofSeconds(30)), "aegis4j", "0.2.0");
mcpClient.initialize();
Retriever mcpRetriever = new McpToolRetriever(mcpClient, "search", McpToolArgumentMapper.defaultMapper());

Aegis4jEngine engine = Aegis4jEngine.builder()
        .provider(OllamaProvider.create())
        .retriever(mcpRetriever) // either one — the engine only knows the Retriever interface
        .build();
```

Retrieval runs on every turn once a `Retriever` is configured (v0.2 =
unconditional, no relevance gating yet), and the result enters the prompt as
a fresh `Context:` block on every call.

## Model routing

Programmatic:

```java
ModelRouter router = new ModelRouter(
        List.of(new KeywordRoutingRule(List.of("code", "bug"), new RouteTarget("ollama", "qwen2.5-coder:7b"))),
        new RouteTarget("ollama", "llama3.1:8b") // required fallback
);

Aegis4jEngine engine = Aegis4jEngine.builder()
        .provider(OllamaProvider.create())
        .modelRouter(router)
        .build();

// omitting providerId/model on ChatRequest lets the router decide; an explicit value always wins
engine.chat(ChatRequest.builder().userInput("help with this bug").build());
```

Declarative (`routing.yaml`):

```yaml
rules:
  - match: {keyword: [code, bug, refactor]}
    provider: ollama
    model: qwen2.5-coder:7b
  - match: {regex: "(?i)\\b(sql|database)\\b"}
    provider: ollama
    model: qwen2.5-coder:7b
default:
  provider: ollama
  model: llama3.1:8b
```

```java
YamlRoutingRuleLoader loader = new YamlRoutingRuleLoader();
ModelRouter router = new ModelRouter(loader.loadFile(path), loader.loadDefaultTarget(path));
```

`providerId`/`model` are resolved **per field** — you can pin one and let the
router decide only the other.

## MCP client

```java
McpTransport transport = new HttpSseMcpTransport(
        URI.create("https://my-mcp-server/mcp"), Map.of(), HttpClient.newHttpClient(), Duration.ofSeconds(30)
); // or StdioMcpTransport(List.of("command", "args"), Map.of(), Duration.ofSeconds(30))

McpClient client = new McpClient(transport, "aegis4j", "0.2.0");
client.initialize();
List<McpToolDescriptor> tools = client.listTools();
McpToolResult result = client.callTool("search", Map.of("query", "pgvector"));
```

Works with any compatible MCP server — the library doesn't assume any
specific server. The MCP protocol's `prompts/*` capability isn't implemented
yet (`listPrompts()` throws `UnsupportedOperationException` until v0.3).

## Modules

| Module                           | Contents                                                          |
|------------------------------------|---------------------------------------------------------------------|
| `aegis4j-api`                    | Contracts: `Provider`, `Guard`, `Skill`, `Persona`                 |
| `aegis4j-core`                   | `Aegis4jEngine`, `GuardChain`, `SkillRegistry`, `ProviderRegistry`  |
| `aegis4j-guardrails-builtin`     | `MaxLengthGuard`, `RegexPiiGuard`                                   |
| `aegis4j-skills`                 | `MarkdownSkillLoader`                                               |
| `aegis4j-provider-http-support`  | HTTP plumbing shared across providers                              |
| `aegis4j-provider-ollama`        | `Provider` for Ollama                                               |
| `aegis4j-server`                 | HTTP sidecar (Javalin)                                              |
| `aegis4j-testkit`                | `FakeProvider`, `FakeRetriever` and test helpers                    |
| `aegis4j-mcp`                     | MCP client (`McpClient`, stdio and HTTP/SSE transports)             |
| `aegis4j-rag-jdbc-pgvector`       | `PgVectorRetriever` (direct JDBC, no ORM)                           |
| `aegis4j-rag-mcp`                 | `McpToolRetriever` (retrieval via any MCP server's tool)            |
| `aegis4j-routing`                 | `KeywordRoutingRule`, `RegexRoutingRule`, `YamlRoutingRuleLoader`    |

## Scope and limitations (v0.2)

**v0.1** (kept): Ollama provider, `MaxLengthGuard` + `RegexPiiGuard` guards,
declarative skills with progressive disclosure, server with
`POST /v1/chat/completions` (non-streaming) and `GET /health`.

**v0.2** (new): full MCP client (stdio + HTTP/SSE, `initialize`/`listTools`/
`callTool`/`listResources`/`readResource`); RAG via `PgVectorRetriever` (JDBC)
and `McpToolRetriever` (MCP), unconditional context injection into the prompt
whenever a `Retriever` is configured; model routing via `ModelRouter` —
programmatic rules (`KeywordRoutingRule`, `RegexRoutingRule`) and declarative
ones (`routing.yaml`), resolved per field (`providerId`/`model` independent),
an explicit value always wins over a routed one.

Out of scope for now (planned for v0.3+):

- Anthropic and OpenAI providers
- SSE streaming on the server (the engine already supports `chatStream`, but
  the server doesn't relay it over SSE yet)
- Programmatic skills registered on the server, persona
- `/v1/skills`, `/v1/guards`, `/v1/models`, `/v1/config` endpoints
- Structured output via `ResponseFormat.JsonSchema` (the contract already
  exists in the API, but isn't validated yet)
- Additional guards: `PromptInjectionHeuristicGuard`, `ProfanityBlocklistGuard`,
  `ContentTypeAllowlistGuard`, `CompetitorMentionGuard`
- The MCP protocol's `prompts/*` (`McpClient.listPrompts()` throws
  `UnsupportedOperationException`)
- A concrete `Embedder` (`OllamaEmbedder`) — which is why
  `AEGIS4J_RETRIEVER=pgvector` on the server throws an explicit error in v0.2;
  wire `PgVectorRetriever` directly via the embedded-library API, passing
  your own `Embedder`
- `RetrievalTriggerStrategy` — v0.2 always retrieves context whenever a
  `Retriever` is configured, no relevance gating
- Per-request `providerId` routing over the HTTP endpoint (only `model` is
  routed on that path in v0.2; `providerId` stays pinned via
  `AEGIS4J_PROVIDER_ID`) — routing both already works via the embedded API
- Connection pooling/retry/circuit-breaking in `PgVectorRetriever`/`McpClient`

Output guards (like `RegexPiiGuard`) need the full text, so they aren't
guaranteed on `chatStream` — only on `chat()` (non-streaming mode).

`PgVectorRetriever` has a real integration test (Testcontainers,
`pgvector/pgvector:pg16`) tagged `requires-docker`, excluded from the default
`build` — run it via
`./gradlew :aegis4j-rag-jdbc-pgvector:pgVectorIntegrationTest` (requires
Docker reachable by Testcontainers).

## License

Apache License 2.0 — the same license used by libraries like Jackson,
Javalin and WireMock. Full text in [`LICENSE`](LICENSE). The project's
dependencies keep their own permissive licenses (Apache 2.0, MIT, BSD, EPL,
as applicable).
