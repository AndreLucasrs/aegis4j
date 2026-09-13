# Aegis4J

🇧🇷 Português | [🇬🇧 English](README.en.md) | [🇪🇸 Español](README.es.md)

Uma lib para o ecossistema JVM (Java, Kotlin, Clojure, Scala) que fica na frente
de LLMs, combinando:

- **Provider abstraction** — troca de LLM em runtime (Ollama, Anthropic, e
  qualquer backend compatível com a API de Chat Completions da OpenAI — a
  própria OpenAI, DeepSeek, Kimi/Moonshot, Groq, servidores locais, etc.)
  atrás de uma interface `Provider` comum.
- **Guardrails determinísticos** — regras que rodam em código puro (regex,
  limites de tamanho, etc.), nunca outra chamada de LLM, aplicadas em input e
  output.
- **Skills com progressive disclosure** — só nome+descrição entram no prompt
  por padrão; o corpo completo de uma skill só é carregado quando ela é
  ativada.
- **Distribuição dupla** — usável como lib embarcada (dependência Gradle/Maven)
  ou como sidecar HTTP (compatível com o formato de request/response da
  OpenAI), então qualquer linguagem/CLI fora da JVM também pode usar.
- **Cliente MCP** (stdio + HTTP/SSE) — conecta em qualquer servidor MCP
  (Model Context Protocol) para expor tools/resources externos.
- **RAG/retrieval extensível** — uma interface `Retriever` única, com
  implementações tanto via pgvector direto (JDBC) quanto via qualquer
  servidor MCP como fonte de busca.
- **Roteamento de modelo por regras** — programáticas (Java) ou declarativas
  (YAML), a pessoa que usa a lib define; primeira regra que casa vence.

Status atual: **v0.2** — ver [Escopo e limitações](#escopo-e-limitações-v02)
no final.

## Requisitos

- Java 17+ (toolchain do build usa Java 17 — qualquer JVM 17 ou mais nova roda
  a lib; não funciona em Java 8/11)
- [Ollama](https://ollama.com) rodando localmente (`http://localhost:11434`)
  se você quiser respostas reais em vez de testes com fakes.

Nenhuma instalação extra é necessária — o wrapper do Gradle (`./gradlew`) já
está no repositório.

## Instalação via JitPack

Assim que o repositório estiver publicado e com uma tag no GitHub, dá pra
adicionar como dependência sem compilar do zero:

**Gradle** (`build.gradle.kts`):

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-core:<tag>")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-provider-ollama:<tag>")
    // qualquer outro módulo: aegis4j-provider-anthropic, aegis4j-provider-openai,
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

`<tag>`/`TAG` é uma tag de release do GitHub (ex: `v0.2.0`) ou um hash de
commit. O groupId `com.github.AndreLucasrs.aegis4j` é o padrão do JitPack
para projeto multi-módulo (usuário.repositório) — cada módulo do projeto vira
um `artifactId` separado, então só declare os módulos que você realmente usa.

## Build e testes

```bash
./gradlew build
```

Compila todos os módulos e roda a suíte de testes (guards, skills, provider
Ollama contra WireMock, e o teste de aceitação do server) — nada disso toca
rede real.

## Rodando o sidecar HTTP

```bash
./gradlew :aegis4j-server:run
```

Variáveis de ambiente (todas opcionais):

| Variável                     | Padrão                   | Descrição                                               |
|-------------------------------|---------------------------|----------------------------------------------------------|
| `AEGIS4J_PORT`                | `8686`                    | Porta HTTP do sidecar                                    |
| `AEGIS4J_PROVIDER_ID`         | `ollama`                  | Provider ativo                                           |
| `AEGIS4J_OLLAMA_BASE_URL`     | `http://localhost:11434`  | Base URL do Ollama                                       |
| `AEGIS4J_MAX_INPUT_CHARS`     | `4000`                    | Teto de caracteres de input (guard `MaxLengthGuard`)      |
| `AEGIS4J_SKILLS_DIR`          | *(nenhum)*                | Diretório com skills declarativas (`.md` + frontmatter)   |
| `AEGIS4J_RETRIEVER`           | *(nenhum)*                | `mcp` — ativa retrieval via MCP (`pgvector` lança erro explícito em v0.2, ver [limitações](#escopo-e-limitações-v02)) |
| `AEGIS4J_MCP_RETRIEVER_TRANSPORT` | *(nenhum)*            | `stdio` ou `http`                                          |
| `AEGIS4J_MCP_RETRIEVER_COMMAND`   | *(nenhum)*            | Comando do servidor MCP, se transporte `stdio`             |
| `AEGIS4J_MCP_RETRIEVER_URL`       | *(nenhum)*            | URL do servidor MCP, se transporte `http`                  |
| `AEGIS4J_MCP_RETRIEVER_TOOL`      | `search`              | Nome da tool MCP usada para busca                           |
| `AEGIS4J_ROUTING_CONFIG`      | *(nenhum)*                | Caminho de um `routing.yaml` — ativa roteamento de `model` por request |
| `AEGIS4J_ANTHROPIC_API_KEY`   | *(nenhum)*                | Chave de API — com `AEGIS4J_PROVIDER_ID=anthropic`, o `AnthropicProvider` se registra automaticamente via `ServiceLoader` |
| `AEGIS4J_OPENAI_COMPATIBLE_ID`      | *(nenhum)*          | Ativa um `OpenAiCompatibleProvider` genérico (ex: `openai`, `deepseek`, `kimi`) — precisa de `_BASE_URL` também |
| `AEGIS4J_OPENAI_COMPATIBLE_BASE_URL`| *(nenhum)*          | Base URL do backend compatível com OpenAI                  |
| `AEGIS4J_OPENAI_COMPATIBLE_API_KEY` | *(nenhum)*          | Chave de API do backend, se precisar                        |

Exemplo com a skill de exemplo (`aegis4j-server/src/main/resources/skills`)
carregada:

```bash
AEGIS4J_SKILLS_DIR="$PWD/aegis4j-server/src/main/resources/skills" \
  ./gradlew :aegis4j-server:run
```

Health check:

```bash
curl http://localhost:8686/health
```

### Chat completions (compatível com o formato OpenAI)

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

Resposta:

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

Se o input ou output contiver e-mail, chave de API, cartão de crédito válido
(checagem de Luhn) ou IPv4, o `RegexPiiGuard` redige automaticamente antes de
seguir no pipeline. Um input maior que `AEGIS4J_MAX_INPUT_CHARS` é bloqueado
com HTTP 400.

## Usando como lib embarcada (Java)

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

Como o core é Java puro, o consumo é interop JVM direto:

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

## Conectando em cada provider

Todos implementam a mesma interface `Provider` — troca em runtime é só
registrar um ou outro. Exemplos:

**Ollama** (local):

```java
providerRegistry.register(OllamaProvider.create()); // lê AEGIS4J_OLLAMA_BASE_URL, default http://localhost:11434
```

**Anthropic**:

```java
providerRegistry.register(AnthropicProvider.create()); // lê AEGIS4J_ANTHROPIC_API_KEY
// ou explícito:
providerRegistry.register(new AnthropicProvider(
        "https://api.anthropic.com", "sk-ant-...", HttpClient.newHttpClient(), Duration.ofSeconds(60)
));

engine.chat(ChatRequest.builder()
        .providerId(AnthropicProvider.ID)
        .model("claude-sonnet-5")
        .userInput("explique o que é pgvector")
        .build());
```

**OpenAI**:

```java
providerRegistry.register(OpenAiCompatibleProvider.openAi()); // lê AEGIS4J_OPENAI_API_KEY

engine.chat(ChatRequest.builder()
        .providerId("openai")
        .model("gpt-5")
        .userInput("explique o que é pgvector")
        .build());
```

**DeepSeek** (API compatível com a da OpenAI):

```java
providerRegistry.register(OpenAiCompatibleProvider.deepSeek()); // lê AEGIS4J_DEEPSEEK_API_KEY

engine.chat(ChatRequest.builder()
        .providerId("deepseek")
        .model("deepseek-chat")
        .userInput("explique o que é pgvector")
        .build());
```

**Kimi (Moonshot AI), Groq, Together AI, OpenRouter, um servidor local
(vLLM/LM Studio), ou qualquer outro backend compatível com a API de Chat
Completions da OpenAI** — use `custom(...)` com o `id`, a `baseUrl` e a
`apiKey` desse provedor. ⚠️ Confirme a base URL exata na documentação atual
do provedor — muda entre eles (alguns usam prefixo `/v1`, outros não) e pode
mudar com o tempo:

```java
providerRegistry.register(OpenAiCompatibleProvider.custom(
        "kimi", "https://api.moonshot.ai/v1", System.getenv("AEGIS4J_KIMI_API_KEY")
));

engine.chat(ChatRequest.builder()
        .providerId("kimi")
        .model("kimi-latest")
        .userInput("explique o que é pgvector")
        .build());
```

`baseUrl` deve incluir tudo até (mas sem incluir) `/chat/completions` — ex:
`https://api.openai.com/v1` para OpenAI, `https://api.deepseek.com` para
DeepSeek (sem `/v1`).

## Criando uma skill declarativa

Crie um arquivo `.md` com frontmatter YAML:

```markdown
---
name: weather-explainer
description: Explains weather concepts in simple, non-technical terms.
triggers: [weather, forecast, clima]
---
You are an expert meteorologist. Explain weather phenomena in plain language...
```

O nome/descrição/triggers entram sempre no catálogo do system prompt; o corpo
só é lido do disco e injetado quando uma das `triggers` aparece no input do
usuário.

## RAG/retrieval

```java
// via pgvector direto (JDBC) — Embedder precisa ser fornecido por quem usa a lib (ver limitações)
Retriever pgvectorRetriever = new PgVectorRetriever(dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"));

// ou via qualquer servidor MCP com uma tool de busca
McpClient mcpClient = new McpClient(new StdioMcpTransport(List.of("meu-mcp-server"), Map.of(), Duration.ofSeconds(30)), "aegis4j", "0.2.0");
mcpClient.initialize();
Retriever mcpRetriever = new McpToolRetriever(mcpClient, "search", McpToolArgumentMapper.defaultMapper());

Aegis4jEngine engine = Aegis4jEngine.builder()
        .provider(OllamaProvider.create())
        .retriever(mcpRetriever) // qualquer um dos dois — o engine só conhece a interface Retriever
        .build();
```

Retrieval roda a cada turno quando um `Retriever` está configurado (v0.2 =
incondicional, sem gating por relevância ainda) e o resultado entra no prompt
como um bloco `Context:` fresco a cada chamada.

## Roteamento de modelo

Programático:

```java
ModelRouter router = new ModelRouter(
        List.of(new KeywordRoutingRule(List.of("code", "bug"), new RouteTarget("ollama", "qwen2.5-coder:7b"))),
        new RouteTarget("ollama", "llama3.1:8b") // fallback obrigatório
);

Aegis4jEngine engine = Aegis4jEngine.builder()
        .provider(OllamaProvider.create())
        .modelRouter(router)
        .build();

// omitir providerId/model no ChatRequest deixa o router decidir; um valor explícito sempre vence
engine.chat(ChatRequest.builder().userInput("ajuda com esse bug").build());
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

`providerId`/`model` são resolvidos **por campo** — dá pra fixar um e deixar
o router decidir só o outro.

## Cliente MCP

```java
McpTransport transport = new HttpSseMcpTransport(
        URI.create("https://meu-mcp-server/mcp"), Map.of(), HttpClient.newHttpClient(), Duration.ofSeconds(30)
); // ou StdioMcpTransport(List.of("comando", "args"), Map.of(), Duration.ofSeconds(30))

McpClient client = new McpClient(transport, "aegis4j", "0.2.0");
client.initialize();
List<McpToolDescriptor> tools = client.listTools();
McpToolResult result = client.callTool("search", Map.of("query", "pgvector"));
```

Funciona com qualquer servidor MCP compatível — a lib não assume nenhum
servidor específico. `prompts/*` do protocolo MCP ainda não é implementado
(`listPrompts()` lança `UnsupportedOperationException` até v0.3).

## Módulos

| Módulo                          | Conteúdo                                                        |
|----------------------------------|------------------------------------------------------------------|
| `aegis4j-api`                    | Contratos: `Provider`, `Guard`, `Skill`, `Persona`               |
| `aegis4j-core`                   | `Aegis4jEngine`, `GuardChain`, `SkillRegistry`, `ProviderRegistry`|
| `aegis4j-guardrails-builtin`     | `MaxLengthGuard`, `RegexPiiGuard`                                 |
| `aegis4j-skills`                 | `MarkdownSkillLoader`                                             |
| `aegis4j-provider-http-support`  | Plumbing HTTP compartilhado entre providers                      |
| `aegis4j-provider-ollama`        | `Provider` para Ollama                                            |
| `aegis4j-provider-openai`         | `OpenAiCompatibleProvider` — OpenAI, DeepSeek, Kimi/Moonshot, etc. |
| `aegis4j-provider-anthropic`      | `Provider` para Anthropic (Messages API)                          |
| `aegis4j-server`                 | Sidecar HTTP (Javalin)                                            |
| `aegis4j-testkit`                | `FakeProvider`, `FakeRetriever` e helpers de teste                 |
| `aegis4j-mcp`                     | Cliente MCP (`McpClient`, transportes stdio e HTTP/SSE)            |
| `aegis4j-rag-jdbc-pgvector`       | `PgVectorRetriever` (JDBC direto, sem ORM)                         |
| `aegis4j-rag-mcp`                 | `McpToolRetriever` (retrieval via tool de qualquer servidor MCP)   |
| `aegis4j-routing`                 | `KeywordRoutingRule`, `RegexRoutingRule`, `YamlRoutingRuleLoader`  |

## Escopo e limitações (v0.2)

**v0.1** (mantido): provider Ollama, guards `MaxLengthGuard` + `RegexPiiGuard`,
skills declarativas com progressive disclosure, server com
`POST /v1/chat/completions` (não-streaming) e `GET /health`.

**v0.2** (novo): cliente MCP completo (stdio + HTTP/SSE, `initialize`/
`listTools`/`callTool`/`listResources`/`readResource`); RAG via
`PgVectorRetriever` (JDBC) e `McpToolRetriever` (MCP), injeção de contexto
incondicional no prompt quando um `Retriever` está configurado; roteamento de
modelo via `ModelRouter` — regras programáticas (`KeywordRoutingRule`,
`RegexRoutingRule`) e declarativas (`routing.yaml`), resolução por campo
(`providerId`/`model` independentes), explícito sempre vence sobre roteado;
`AnthropicProvider` (Messages API) e `OpenAiCompatibleProvider` (cobre OpenAI,
DeepSeek, Kimi/Moonshot e qualquer outro backend compatível com a API de Chat
Completions da OpenAI).

Fora de escopo por ora (planejado para v0.3+):

- Streaming SSE no server (o engine já suporta `chatStream`, mas o server
  ainda não relaya isso via SSE)
- Skills programáticas registradas no server, persona
- Endpoints `/v1/skills`, `/v1/guards`, `/v1/models`, `/v1/config`
- Output estruturado via `ResponseFormat.JsonSchema` (o contrato já existe na
  API, mas não é validado ainda)
- Guards adicionais: `PromptInjectionHeuristicGuard`, `ProfanityBlocklistGuard`,
  `ContentTypeAllowlistGuard`, `CompetitorMentionGuard`
- `prompts/*` do protocolo MCP (`McpClient.listPrompts()` lança
  `UnsupportedOperationException`)
- `Embedder` concreto (`OllamaEmbedder`) — por isso `AEGIS4J_RETRIEVER=pgvector`
  no server lança erro explícito em v0.2; use `PgVectorRetriever` direto via a
  API embarcada, passando seu próprio `Embedder`
- `RetrievalTriggerStrategy` — v0.2 sempre recupera contexto quando há
  `Retriever` configurado, sem gating por relevância
- Roteamento de `providerId` por request via o endpoint HTTP (só `model` é
  roteado nesse caminho em v0.2; `providerId` continua fixo via
  `AEGIS4J_PROVIDER_ID`) — via API embarcada o roteamento de ambos já funciona
- Connection pooling/retry/circuit-breaker em `PgVectorRetriever`/`McpClient`

Guards de output (como o `RegexPiiGuard`) exigem o texto completo, então em
`chatStream` eles não são garantidos — só em `chat()` (modo não-streaming).

`PgVectorRetriever` tem um teste de integração real (Testcontainers,
`pgvector/pgvector:pg16`) tagueado `requires-docker`, fora do `build` padrão —
rode via `./gradlew :aegis4j-rag-jdbc-pgvector:pgVectorIntegrationTest`
(requer Docker acessível pelo Testcontainers).

## Licença

Apache License 2.0 — mesma licença de libs como Jackson, Javalin e WireMock.
Texto completo em [`LICENSE`](LICENSE). As dependências do projeto mantêm suas
próprias licenças permissivas (Apache 2.0, MIT, BSD, EPL, conforme o caso).
