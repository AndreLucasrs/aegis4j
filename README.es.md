# Aegis4J

[🇧🇷 Português](README.md) | [🇬🇧 English](README.en.md) | 🇪🇸 Español

Una librería para el ecosistema JVM (Java, Kotlin, Clojure, Scala) que se
sitúa delante de los LLM, combinando:

- **Abstracción de proveedor** — cambia de proveedor de LLM en tiempo de
  ejecución (Ollama, Anthropic, y cualquier backend compatible con la API de
  Chat Completions de OpenAI — la propia OpenAI, DeepSeek, Kimi/Moonshot,
  Groq, servidores locales, etc.) detrás de una interfaz `Provider` común.
- **Guardrails deterministas** — reglas que se ejecutan en código puro
  (regex, límites de longitud, etc.), nunca otra llamada a un LLM, aplicadas
  tanto en la entrada como en la salida.
- **Skills con divulgación progresiva** — solo nombre+descripción entran en
  el prompt por defecto; el cuerpo completo de una skill solo se carga cuando
  se activa.
- **Distribución dual** — usable como librería embebida (dependencia
  Gradle/Maven) o como sidecar HTTP (formato de request/response compatible
  con OpenAI), así que cualquier lenguaje/CLI fuera de la JVM también puede
  usarla.
- **Cliente MCP** (stdio + HTTP/SSE) — se conecta a cualquier servidor MCP
  (Model Context Protocol) para exponer tools/resources externos.
- **RAG/retrieval extensible** — una única interfaz `Retriever`, con
  implementaciones tanto vía pgvector directo (JDBC) como vía cualquier
  servidor MCP como fuente de búsqueda.
- **Enrutamiento de modelo por reglas** — programáticas (Java) o
  declarativas (YAML), lo que defina quien use la librería; gana la primera
  regla que coincide.

Estado actual: **v0.2** — ver [Alcance y limitaciones](#alcance-y-limitaciones-v02)
al final.

## Requisitos

- Java 17+ (el toolchain del build usa Java 17 — cualquier JVM 17 o más
  reciente ejecuta la librería; no funciona en Java 8/11)
- [Ollama](https://ollama.com) corriendo localmente (`http://localhost:11434`)
  si querés respuestas reales en lugar de pruebas con fakes.

No se necesita instalación extra — el wrapper de Gradle (`./gradlew`) ya está
en el repositorio.

## Instalación vía JitPack

El código está publicado en [github.com/AndreLucasrs/aegis4j](https://github.com/AndreLucasrs/aegis4j)
y la tag `v0.2.0` ya builda en [JitPack](https://jitpack.io/#AndreLucasrs/aegis4j) — se puede
agregar como dependencia sin compilar desde cero:

**Gradle** (`build.gradle.kts`):

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-core:<tag>")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-provider-ollama:<tag>")
    // cualquier otro módulo: aegis4j-provider-anthropic, aegis4j-provider-openai,
    // aegis4j-mcp, aegis4j-rag-jdbc-pgvector, aegis4j-rag-mcp, aegis4j-routing, ...
}
```

**Maven** (`pom.xml`):

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.AndreLucasrs.aegis4j</groupId>
    <artifactId>aegis4j-core</artifactId>
    <version>TAG</version>
</dependency>
```

`<tag>`/`TAG` es una tag de release de GitHub (ej: `v0.2.0`) o un hash de
commit. El groupId `com.github.AndreLucasrs.aegis4j` es el patrón estándar de
JitPack para un proyecto multi-módulo (usuario.repositorio) — cada módulo se
convierte en su propio `artifactId`, así que declará solo los que realmente
usás.

## Build y pruebas

```bash
./gradlew build
```

Compila todos los módulos y ejecuta la suite de pruebas completa (guards,
skills, el provider de Ollama contra WireMock, y la prueba de aceptación del
server) — nada de esto toca red real.

## Ejecutando el sidecar HTTP

```bash
./gradlew :aegis4j-server:run
```

Variables de entorno (todas opcionales):

| Variable                     | Valor por defecto        | Descripción                                                |
|-------------------------------|---------------------------|----------------------------------------------------------|
| `AEGIS4J_PORT`                | `8686`                    | Puerto HTTP del sidecar                                    |
| `AEGIS4J_PROVIDER_ID`         | `ollama`                  | Provider activo                                             |
| `AEGIS4J_OLLAMA_BASE_URL`     | `http://localhost:11434`  | URL base de Ollama                                          |
| `AEGIS4J_MAX_INPUT_CHARS`     | `4000`                    | Tope de caracteres de entrada (`MaxLengthGuard`)            |
| `AEGIS4J_SKILLS_DIR`          | *(ninguno)*                | Directorio con skills declarativas (`.md` + frontmatter)    |
| `AEGIS4J_RETRIEVER`           | *(ninguno)*                | `mcp` — activa retrieval vía MCP (`pgvector` lanza un error explícito en v0.2, ver [limitaciones](#alcance-y-limitaciones-v02)) |
| `AEGIS4J_MCP_RETRIEVER_TRANSPORT` | *(ninguno)*            | `stdio` o `http`                                             |
| `AEGIS4J_MCP_RETRIEVER_COMMAND`   | *(ninguno)*            | Comando del servidor MCP, si el transporte es `stdio`        |
| `AEGIS4J_MCP_RETRIEVER_URL`       | *(ninguno)*            | URL del servidor MCP, si el transporte es `http`             |
| `AEGIS4J_MCP_RETRIEVER_TOOL`      | `search`              | Nombre de la tool MCP usada para la búsqueda                  |
| `AEGIS4J_ROUTING_CONFIG`      | *(ninguno)*                | Ruta de un `routing.yaml` — activa el enrutamiento de `model` por request |
| `AEGIS4J_ANTHROPIC_API_KEY`   | *(ninguno)*                | Clave de API — con `AEGIS4J_PROVIDER_ID=anthropic`, `AnthropicProvider` se registra solo vía `ServiceLoader` |
| `AEGIS4J_OPENAI_COMPATIBLE_ID`      | *(ninguno)*          | Activa un `OpenAiCompatibleProvider` genérico (ej: `openai`, `deepseek`, `kimi`) — necesita `_BASE_URL` también |
| `AEGIS4J_OPENAI_COMPATIBLE_BASE_URL`| *(ninguno)*          | URL base del backend compatible con OpenAI                  |
| `AEGIS4J_OPENAI_COMPATIBLE_API_KEY` | *(ninguno)*          | Clave de API de ese backend, si hace falta                   |

Ejemplo con la skill de ejemplo (`aegis4j-server/src/main/resources/skills`)
cargada:

```bash
AEGIS4J_SKILLS_DIR="$PWD/aegis4j-server/src/main/resources/skills" \
  ./gradlew :aegis4j-server:run
```

Health check:

```bash
curl http://localhost:8686/health
```

### Chat completions (formato compatible con OpenAI)

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

Respuesta:

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

Si la entrada o la salida contiene un email, una clave de API, una tarjeta de
crédito válida (verificada con Luhn) o una IPv4, `RegexPiiGuard` la redacta
automáticamente antes de continuar en el pipeline. Una entrada más larga que
`AEGIS4J_MAX_INPUT_CHARS` se bloquea con HTTP 400.

## Usándola como librería embebida (Java)

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

Como el core es Java puro, el consumo es interop de JVM directo:

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

## Conectando cada provider

Todos implementan la misma interfaz `Provider` — cambiar en runtime es solo
registrar uno u otro. Ejemplos:

**Ollama** (local):

```java
providerRegistry.register(OllamaProvider.create()); // lee AEGIS4J_OLLAMA_BASE_URL, por defecto http://localhost:11434
```

**Anthropic**:

```java
providerRegistry.register(AnthropicProvider.create()); // lee AEGIS4J_ANTHROPIC_API_KEY
// o explícito:
providerRegistry.register(new AnthropicProvider(
        "https://api.anthropic.com", "sk-ant-...", HttpClient.newHttpClient(), Duration.ofSeconds(60)
));

engine.chat(ChatRequest.builder()
        .providerId(AnthropicProvider.ID)
        .model("claude-sonnet-5")
        .userInput("explicá qué es pgvector")
        .build());
```

**OpenAI**:

```java
providerRegistry.register(OpenAiCompatibleProvider.openAi()); // lee AEGIS4J_OPENAI_API_KEY

engine.chat(ChatRequest.builder()
        .providerId("openai")
        .model("gpt-5")
        .userInput("explicá qué es pgvector")
        .build());
```

**DeepSeek** (API compatible con la de OpenAI):

```java
providerRegistry.register(OpenAiCompatibleProvider.deepSeek()); // lee AEGIS4J_DEEPSEEK_API_KEY

engine.chat(ChatRequest.builder()
        .providerId("deepseek")
        .model("deepseek-chat")
        .userInput("explicá qué es pgvector")
        .build());
```

**Kimi (Moonshot AI), Groq, Together AI, OpenRouter, un servidor local
(vLLM/LM Studio), o cualquier otro backend compatible con la API de Chat
Completions de OpenAI** — usá `custom(...)` con el `id`, la `baseUrl` y la
`apiKey` de ese proveedor. ⚠️ Confirmá la base URL exacta en la documentación
actual del proveedor — cambia entre ellos (algunos usan prefijo `/v1`, otros
no) y puede cambiar con el tiempo:

```java
providerRegistry.register(OpenAiCompatibleProvider.custom(
        "kimi", "https://api.moonshot.ai/v1", System.getenv("AEGIS4J_KIMI_API_KEY")
));

engine.chat(ChatRequest.builder()
        .providerId("kimi")
        .model("kimi-latest")
        .userInput("explicá qué es pgvector")
        .build());
```

`baseUrl` debe incluir todo hasta (pero sin incluir) `/chat/completions` —
ej: `https://api.openai.com/v1` para OpenAI, `https://api.deepseek.com` para
DeepSeek (sin `/v1`).

### Circuit breaker / resiliencia

Aegis4J no incluye circuit breaker — cada `Provider`/`McpTransport`/
`PgVectorRetriever` solo tiene un `Duration timeout` explícito por llamada,
lo que ya evita quedarse esperando indefinidamente en una llamada individual,
pero no evita repetir el intento contra un servicio que ya se sabe caído.
Esto es deliberado: el umbral de fallo, el tiempo de half-open y qué cuenta
como "fallo" son decisiones de política que varían según el entorno, y mucha
gente ya corre Resilience4j, service mesh o health-checks de load balancer —
un circuit breaker interno solo competiría con eso.

Como `Provider` es solo una interfaz, podés envolverla con lo que ya usás,
sin tocar la librería. Ejemplo con
[Resilience4j](https://resilience4j.readme.io/):

```java
CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .slidingWindowSize(10)
        .build();
CircuitBreaker circuitBreaker = CircuitBreaker.of("ollama", config);

Provider delegate = OllamaProvider.create();
Provider resilientProvider = new Provider() {
    @Override public String id() { return delegate.id(); }
    @Override public CompletionResponse complete(CompletionRequest request) {
        return circuitBreaker.executeSupplier(() -> delegate.complete(request));
    }
    @Override public Stream<CompletionChunk> stream(CompletionRequest request) {
        return circuitBreaker.executeSupplier(() -> delegate.stream(request));
    }
    @Override public List<ModelInfo> listModels() {
        return circuitBreaker.executeSupplier(delegate::listModels);
    }
};

providerRegistry.register(resilientProvider);
```

Con el circuito abierto, `executeSupplier` lanza `CallNotPermittedException`
(unchecked) — se propaga a través de `Aegis4jEngine.chat()` igual que ya
propaga una `ProviderException` hoy, sin necesitar tratamiento especial en
el engine. El mismo patrón de decorator funciona para `Retriever` y
`McpTransport`.

## Creando una skill declarativa

Creá un archivo `.md` con un bloque de frontmatter YAML:

```markdown
---
name: weather-explainer
description: Explains weather concepts in simple, non-technical terms.
triggers: [weather, forecast, clima]
---
You are an expert meteorologist. Explain weather phenomena in plain language...
```

El nombre/descripción/triggers siempre entran en el catálogo del system
prompt; el cuerpo solo se lee del disco y se inyecta cuando uno de los
`triggers` aparece en la entrada del usuario.

## RAG/retrieval

```java
// vía pgvector directo (JDBC) — el Embedder debe ser provisto por quien usa la librería (ver limitaciones)
Retriever pgvectorRetriever = new PgVectorRetriever(dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"));

// o vía cualquier servidor MCP con una tool de búsqueda
McpClient mcpClient = new McpClient(new StdioMcpTransport(List.of("mi-servidor-mcp"), Map.of(), Duration.ofSeconds(30)), "aegis4j", "0.2.0");
mcpClient.initialize();
Retriever mcpRetriever = new McpToolRetriever(mcpClient, "search", McpToolArgumentMapper.defaultMapper());

Aegis4jEngine engine = Aegis4jEngine.builder()
        .provider(OllamaProvider.create())
        .retriever(mcpRetriever) // cualquiera de los dos — el engine solo conoce la interfaz Retriever
        .build();
```

El retrieval se ejecuta en cada turno cuando hay un `Retriever` configurado
(v0.2 = incondicional, todavía sin filtrado por relevancia) y el resultado
entra en el prompt como un bloque `Context:` fresco en cada llamada.

## Enrutamiento de modelo

Programático:

```java
ModelRouter router = new ModelRouter(
        List.of(new KeywordRoutingRule(List.of("code", "bug"), new RouteTarget("ollama", "qwen2.5-coder:7b"))),
        new RouteTarget("ollama", "llama3.1:8b") // fallback obligatorio
);

Aegis4jEngine engine = Aegis4jEngine.builder()
        .provider(OllamaProvider.create())
        .modelRouter(router)
        .build();

// omitir providerId/model en ChatRequest deja que el router decida; un valor explícito siempre gana
engine.chat(ChatRequest.builder().userInput("ayuda con este bug").build());
```

Declarativo (`routing.yaml`):

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

`providerId`/`model` se resuelven **por campo** — podés fijar uno y dejar que
el router decida solo el otro.

## Cliente MCP

```java
McpTransport transport = new HttpSseMcpTransport(
        URI.create("https://mi-servidor-mcp/mcp"), Map.of(), HttpClient.newHttpClient(), Duration.ofSeconds(30)
); // o StdioMcpTransport(List.of("comando", "args"), Map.of(), Duration.ofSeconds(30))

McpClient client = new McpClient(transport, "aegis4j", "0.2.0");
client.initialize();
List<McpToolDescriptor> tools = client.listTools();
McpToolResult result = client.callTool("search", Map.of("query", "pgvector"));
```

Funciona con cualquier servidor MCP compatible — la librería no asume ningún
servidor específico. La capacidad `prompts/*` del protocolo MCP todavía no
está implementada (`listPrompts()` lanza `UnsupportedOperationException`
hasta v0.3).

## Módulos

| Módulo                           | Contenido                                                          |
|------------------------------------|-----------------------------------------------------------------|
| `aegis4j-api`                    | Contratos: `Provider`, `Guard`, `Skill`, `Persona`                  |
| `aegis4j-core`                   | `Aegis4jEngine`, `GuardChain`, `SkillRegistry`, `ProviderRegistry`   |
| `aegis4j-guardrails-builtin`     | `MaxLengthGuard`, `RegexPiiGuard`                                    |
| `aegis4j-skills`                 | `MarkdownSkillLoader`                                                |
| `aegis4j-provider-http-support`  | Plumbing HTTP compartido entre providers                            |
| `aegis4j-provider-ollama`        | `Provider` para Ollama                                               |
| `aegis4j-provider-openai`         | `OpenAiCompatibleProvider` — OpenAI, DeepSeek, Kimi/Moonshot, etc.  |
| `aegis4j-provider-anthropic`      | `Provider` para Anthropic (Messages API)                            |
| `aegis4j-server`                 | Sidecar HTTP (Javalin)                                                |
| `aegis4j-testkit`                | `FakeProvider`, `FakeRetriever` y helpers de prueba                  |
| `aegis4j-mcp`                     | Cliente MCP (`McpClient`, transportes stdio y HTTP/SSE)              |
| `aegis4j-rag-jdbc-pgvector`       | `PgVectorRetriever` (JDBC directo, sin ORM)                          |
| `aegis4j-rag-mcp`                 | `McpToolRetriever` (retrieval vía la tool de cualquier servidor MCP) |
| `aegis4j-routing`                 | `KeywordRoutingRule`, `RegexRoutingRule`, `YamlRoutingRuleLoader`     |

## Alcance y limitaciones (v0.2)

**v0.1** (se mantiene): provider Ollama, guards `MaxLengthGuard` +
`RegexPiiGuard`, skills declarativas con divulgación progresiva, server con
`POST /v1/chat/completions` (sin streaming) y `GET /health`.

**v0.2** (nuevo): cliente MCP completo (stdio + HTTP/SSE, `initialize`/
`listTools`/`callTool`/`listResources`/`readResource`); RAG vía
`PgVectorRetriever` (JDBC) y `McpToolRetriever` (MCP), inyección de contexto
incondicional en el prompt cuando hay un `Retriever` configurado;
enrutamiento de modelo vía `ModelRouter` — reglas programáticas
(`KeywordRoutingRule`, `RegexRoutingRule`) y declarativas (`routing.yaml`),
resueltas por campo (`providerId`/`model` independientes), un valor explícito
siempre gana sobre uno enrutado; `AnthropicProvider` (Messages API) y
`OpenAiCompatibleProvider` (cubre OpenAI, DeepSeek, Kimi/Moonshot y cualquier
otro backend compatible con la API de Chat Completions de OpenAI).

Fuera de alcance por ahora (planeado para v0.3+):

- Streaming SSE en el server (el engine ya soporta `chatStream`, pero el
  server todavía no lo retransmite vía SSE)
- Skills programáticas registradas en el server, persona
- Endpoints `/v1/skills`, `/v1/guards`, `/v1/models`, `/v1/config`
- Salida estructurada vía `ResponseFormat.JsonSchema` (el contrato ya existe
  en la API, pero todavía no se valida)
- Guards adicionales: `PromptInjectionHeuristicGuard`,
  `ProfanityBlocklistGuard`, `ContentTypeAllowlistGuard`,
  `CompetitorMentionGuard`
- La capacidad `prompts/*` del protocolo MCP (`McpClient.listPrompts()` lanza
  `UnsupportedOperationException`)
- Un `Embedder` concreto (`OllamaEmbedder`) — por eso
  `AEGIS4J_RETRIEVER=pgvector` en el server lanza un error explícito en v0.2;
  usá `PgVectorRetriever` directamente vía la API embebida, pasando tu propio
  `Embedder`
- `RetrievalTriggerStrategy` — v0.2 siempre recupera contexto cuando hay un
  `Retriever` configurado, sin filtrado por relevancia
- Enrutamiento de `providerId` por request vía el endpoint HTTP (en v0.2 solo
  se enruta `model` en ese camino; `providerId` sigue fijo vía
  `AEGIS4J_PROVIDER_ID`) — vía la API embebida ya funciona el enrutamiento de
  ambos
- Connection pooling/retry/circuit-breaker en `PgVectorRetriever`/`McpClient`

Los guards de salida (como `RegexPiiGuard`) necesitan el texto completo, así
que no están garantizados en `chatStream` — solo en `chat()` (modo sin
streaming).

`PgVectorRetriever` tiene una prueba de integración real (Testcontainers,
`pgvector/pgvector:pg16`) etiquetada `requires-docker`, excluida del `build`
por defecto — ejecutala vía
`./gradlew :aegis4j-rag-jdbc-pgvector:pgVectorIntegrationTest` (requiere que
Testcontainers pueda acceder a Docker).

## Licencia

Apache License 2.0 — la misma licencia que usan librerías como Jackson,
Javalin y WireMock. Texto completo en [`LICENSE`](LICENSE). Las dependencias
del proyecto mantienen sus propias licencias permisivas (Apache 2.0, MIT,
BSD, EPL, según corresponda).
