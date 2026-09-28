package dev.aegis4j.core.engine;

import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.persona.Persona;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.CompletionRequest;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.provider.Provider;
import dev.aegis4j.api.provider.ToolCall;
import dev.aegis4j.api.provider.ToolDefinition;
import dev.aegis4j.api.provider.ToolExecutor;
import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.api.rag.Retriever;
import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.routing.RoutingContext;
import dev.aegis4j.core.guard.GuardChain;
import dev.aegis4j.core.persona.PersonaManager;
import dev.aegis4j.core.prompt.PromptAssembler;
import dev.aegis4j.core.provider.ProviderRegistry;
import dev.aegis4j.core.routing.ModelRouter;
import dev.aegis4j.core.skill.KeywordSkillActivationStrategy;
import dev.aegis4j.core.skill.SkillActivationStrategy;
import dev.aegis4j.core.skill.SkillRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Wires provider, guards, skills, persona, retrieval and routing into one
 * request pipeline: input guards → retrieval (if configured) → route
 * resolution (if provider/model absent) → prompt assembly → provider call →
 * output guards.
 */
public final class Aegis4jEngine {

    private static final int DEFAULT_TOP_K = 4;
    static final int DEFAULT_MAX_TOOL_ITERATIONS = 5;

    private final ProviderRegistry providerRegistry;
    private final GuardChain guardChain;
    private final SkillRegistry skillRegistry;
    private final SkillActivationStrategy activationStrategy;
    private final boolean includeSkillCatalogInSystemPrompt;
    private final PersonaManager personaManager;
    private final Retriever retriever;
    private final ModelRouter modelRouter;
    private final List<ToolDefinition> toolDefinitions;
    private final ToolExecutor toolExecutor;
    private final int maxToolIterations;
    private final PromptAssembler promptAssembler = new PromptAssembler();

    private Aegis4jEngine(Builder builder) {
        this.providerRegistry = builder.providerRegistry;
        this.guardChain = builder.guardChain;
        this.skillRegistry = builder.skillRegistry;
        this.activationStrategy = builder.activationStrategy;
        this.includeSkillCatalogInSystemPrompt = builder.skillCatalogInSystemPrompt != null
                ? builder.skillCatalogInSystemPrompt
                : builder.activationStrategy.includeCatalogInSystemPrompt();
        this.personaManager = builder.personaManager;
        this.retriever = builder.retriever;
        this.modelRouter = builder.modelRouter;
        this.toolDefinitions = builder.toolDefinitions;
        this.toolExecutor = builder.toolExecutor;
        this.maxToolIterations = builder.maxToolIterations;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletionResponse chat(ChatRequest request) {
        GuardContext ctx = new GuardContext(request.requestId(), request.userId(), Map.of());

        String sanitizedInput = guardChain.runInput(ctx, request.userInput());
        List<RetrievedChunk> retrievedChunks = resolveChunks(sanitizedInput, request);
        ResolvedRoute route = resolveRoute(sanitizedInput, request);

        List<Message> messages = promptAssembler.assemble(
                personaManager.active(),
                skillRegistry,
                activationStrategy,
                includeSkillCatalogInSystemPrompt,
                retrievedChunks,
                request.history(),
                sanitizedInput
        );

        Provider provider = providerRegistry.resolve(route.providerId());
        CompletionRequest completionRequest = CompletionRequest.builder()
                .model(route.model())
                .messages(messages)
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .tools(toolDefinitions)
                .build();

        CompletionResponse response = provider.complete(completionRequest);

        if (toolExecutor != null) {
            response = runToolLoop(provider, route, request, messages, response);
        }

        // response.content() can legitimately be null after a tool-call-only
        // turn (see the OpenAI wire format): guards operate on text, not on
        // the absence of it, so an empty string goes through instead of null.
        String sanitizedOutput = guardChain.runOutput(ctx, response.content() == null ? "" : response.content());

        return new CompletionResponse(
                response.id(),
                response.model(),
                sanitizedOutput,
                response.finishReason(),
                response.usage(),
                response.toolCalls()
        );
    }

    /**
     * Only entered when a {@link ToolExecutor} is configured, so callers that
     * never opt in keep getting exactly the old single-round-trip behavior.
     * Each iteration appends the model's tool-call request and every tool's
     * result to the conversation and calls the provider again; the loop ends
     * as soon as a response comes back with no pending tool calls.
     *
     * <p>{@code providerCalls} starts at 1 because {@code response} is
     * already the result of one {@code provider.complete()} call made before
     * this method was entered — {@link #maxToolIterations} is a cap on the
     * total number of provider calls per {@link #chat}, not on how many
     * extra round trips this loop itself makes, so a default of 5 means at
     * most 5 calls to the provider altogether, not 6.
     */
    private CompletionResponse runToolLoop(
            Provider provider, ResolvedRoute route, ChatRequest request, List<Message> messages, CompletionResponse response
    ) {
        List<Message> conversation = new ArrayList<>(messages);
        int providerCalls = 1;

        while (!response.toolCalls().isEmpty()) {
            if (providerCalls >= maxToolIterations) {
                throw new ToolCallLimitExceededException(maxToolIterations);
            }

            conversation.add(Message.assistantToolCall(response.content(), response.toolCalls()));
            for (ToolCall call : response.toolCalls()) {
                conversation.add(Message.toolResult(call.id(), executeTool(call)));
            }

            CompletionRequest followUp = CompletionRequest.builder()
                    .model(route.model())
                    .messages(conversation)
                    .temperature(request.temperature())
                    .maxTokens(request.maxTokens())
                    .tools(toolDefinitions)
                    .build();
            response = provider.complete(followUp);
            providerCalls++;
        }

        return response;
    }

    /**
     * A failing tool is fed back to the model as its result instead of
     * aborting the whole exchange — the model can then retry, work around it
     * or explain the failure, the same way {@code McpClient.callTool} reports
     * a tool error in-band rather than throwing. The iteration cap above is
     * the actual safety net against a model that never stops calling tools.
     * Only the exception's class name reaches the model, never
     * {@code e.getMessage()} — see {@link ToolExecutor} for why.
     */
    private String executeTool(ToolCall call) {
        try {
            return toolExecutor.execute(call);
        } catch (RuntimeException e) {
            return "Tool \"" + call.name() + "\" failed (" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * v1 limitation: output guards need the full text, which is in tension
     * with token-by-token streaming. This method only runs input guards,
     * retrieval, routing and prompt assembly — output guards are NOT applied
     * to streamed chunks. Callers that need guaranteed output guarding must
     * use {@link #chat}.
     *
     * <p>Also does not implement tool calling: even when {@link Builder#tools}
     * is configured, this method never sends {@code tools} on the request
     * and never inspects the stream for tool calls — that loop only exists
     * in {@link #chat}. Callers that configure tools and use this method
     * instead will see the feature silently do nothing.
     */
    public Stream<dev.aegis4j.api.provider.CompletionChunk> chatStream(ChatRequest request) {
        GuardContext ctx = new GuardContext(request.requestId(), request.userId(), Map.of());

        String sanitizedInput = guardChain.runInput(ctx, request.userInput());
        List<RetrievedChunk> retrievedChunks = resolveChunks(sanitizedInput, request);
        ResolvedRoute route = resolveRoute(sanitizedInput, request);

        List<Message> messages = promptAssembler.assemble(
                personaManager.active(),
                skillRegistry,
                activationStrategy,
                includeSkillCatalogInSystemPrompt,
                retrievedChunks,
                request.history(),
                sanitizedInput
        );

        Provider provider = providerRegistry.resolve(route.providerId());
        CompletionRequest completionRequest = CompletionRequest.builder()
                .model(route.model())
                .messages(messages)
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .build();

        return provider.stream(completionRequest);
    }

    /**
     * v1: retrieves unconditionally on every turn once a {@link Retriever} is
     * configured — mirrors how {@code KeywordSkillActivationStrategy} always
     * evaluates rather than deciding whether to look. A v0.2+
     * {@code RetrievalTriggerStrategy} interface (analogous to
     * {@code SkillActivationStrategy}) is the natural seam for smarter
     * gating without changing this method's signature.
     */
    private List<RetrievedChunk> resolveChunks(String sanitizedInput, ChatRequest request) {
        if (retriever == null) {
            return List.of();
        }
        int topK = request.topK() != null ? request.topK() : DEFAULT_TOP_K;
        return retriever.retrieve(sanitizedInput, topK);
    }

    /**
     * Resolves providerId/model per-field: an explicit value on the request
     * always wins over routing. Only consults {@link #modelRouter} when at
     * least one of the two is absent, and only for the absent field(s).
     */
    private ResolvedRoute resolveRoute(String sanitizedInput, ChatRequest request) {
        String providerId = request.providerId();
        String model = request.model();
        if (providerId == null || model == null) {
            if (modelRouter == null) {
                throw new IllegalStateException(
                        "ChatRequest.providerId/model were not set and no ModelRouter is configured on Aegis4jEngine");
            }
            RouteTarget route = modelRouter.resolve(new RoutingContext(sanitizedInput, request.history(), request.metadata()));
            providerId = providerId != null ? providerId : route.providerId();
            model = model != null ? model : route.model();
        }
        return new ResolvedRoute(providerId, model);
    }

    private record ResolvedRoute(String providerId, String model) {
    }

    public static final class Builder {
        private ProviderRegistry providerRegistry = new ProviderRegistry();
        private GuardChain guardChain = GuardChain.of();
        private SkillRegistry skillRegistry = SkillRegistry.inMemory();
        private SkillActivationStrategy activationStrategy = new KeywordSkillActivationStrategy();
        private PersonaManager personaManager = PersonaManager.none();
        private Retriever retriever;
        private ModelRouter modelRouter;
        private Boolean skillCatalogInSystemPrompt;
        private List<ToolDefinition> toolDefinitions = List.of();
        private ToolExecutor toolExecutor;
        private int maxToolIterations = DEFAULT_MAX_TOOL_ITERATIONS;

        public Builder providerRegistry(ProviderRegistry providerRegistry) {
            this.providerRegistry = providerRegistry;
            return this;
        }

        public Builder provider(Provider provider) {
            this.providerRegistry.register(provider);
            return this;
        }

        public Builder guardChain(GuardChain guardChain) {
            this.guardChain = guardChain;
            return this;
        }

        public Builder skillRegistry(SkillRegistry skillRegistry) {
            this.skillRegistry = skillRegistry;
            return this;
        }

        public Builder activationStrategy(SkillActivationStrategy activationStrategy) {
            this.activationStrategy = activationStrategy;
            return this;
        }

        /**
         * Overrides whether the skill catalog (name + description of every
         * registered skill) is listed in the system prompt on every turn,
         * regardless of the configured {@link SkillActivationStrategy}'s own
         * {@link SkillActivationStrategy#includeCatalogInSystemPrompt()}.
         * Not calling this leaves that decision to the strategy (which
         * defaults to {@code true}), so existing behavior is unchanged
         * unless a consumer opts out explicitly. Resolved once at
         * {@link #build()} time, so this is safe to call before or after
         * {@link #activationStrategy} and safe to call repeatedly on a
         * reused {@code Builder}.
         */
        public Builder skillCatalogInSystemPrompt(boolean include) {
            this.skillCatalogInSystemPrompt = include;
            return this;
        }

        public Builder persona(Persona persona) {
            this.personaManager = new PersonaManager(persona);
            return this;
        }

        /** {@code null} is a safe no-op — leaves retrieval disabled (or whatever was set before). */
        public Builder retriever(Retriever retriever) {
            if (retriever != null) {
                this.retriever = retriever;
            }
            return this;
        }

        /** {@code null} is a safe no-op — leaves routing disabled (or whatever was set before). */
        public Builder modelRouter(ModelRouter modelRouter) {
            if (modelRouter != null) {
                this.modelRouter = modelRouter;
            }
            return this;
        }

        /**
         * Opts into the tool-calling loop: off by default (a fresh
         * {@code Builder} sends no {@code tools} to the provider and never
         * inspects {@code response.toolCalls()}) so existing consumers such
         * as Janus, which embed {@code Aegis4jEngine} directly, keep their
         * current behavior unless they call this explicitly. {@code executor}
         * is always caller-supplied — {@code aegis4j-core} ships no
         * executor of its own, since deciding what is safe to run is the
         * embedder's call, not the library's.
         *
         * <p><b>Provider support is partial as of this writing:</b> only
         * {@code OpenAiCompatibleProvider} actually sends {@code tools} on
         * the wire and parses {@code tool_calls} back — {@code OllamaProvider}
         * and {@code AnthropicProvider} silently ignore {@code tools} (no
         * error, no log) and will never return a tool call, so the loop
         * this method enables will never trigger against them. Route tool
         * calling to an {@code OpenAiCompatibleProvider}-backed model until
         * the other two providers gain equivalent support.
         *
         * <p><b>Security:</b> the guard chain only sanitizes the initial
         * user input and the final answer — see {@link ToolExecutor} for why
         * intermediate tool results and model reasoning inside the loop are
         * never guarded.
         *
         * @throws IllegalArgumentException if {@code definitions} is
         *         non-null but {@code executor} is null — that combination
         *         is indistinguishable from a caller mistake (definitions
         *         with nothing to run them) rather than "leave tools
         *         disabled", which is instead {@code executor == null} with
         *         {@code definitions == null}.
         */
        public Builder tools(List<ToolDefinition> definitions, ToolExecutor executor) {
            if (executor == null) {
                if (definitions != null) {
                    throw new IllegalArgumentException(
                            "Aegis4jEngine.Builder.tools(definitions, executor): executor must not be null "
                                    + "when tool definitions are provided; pass both null to leave tools disabled");
                }
                return this;
            }
            this.toolDefinitions = definitions == null ? List.of() : List.copyOf(definitions);
            this.toolExecutor = executor;
            return this;
        }

        /**
         * Hard cap on the total number of {@code provider.complete()} calls
         * {@link #chat} makes for a single request, including the first one —
         * defaults to {@value #DEFAULT_MAX_TOOL_ITERATIONS}, so by default
         * {@code chat()} calls the provider at most 5 times total, not 5
         * times in addition to the initial call.
         */
        public Builder maxToolIterations(int maxToolIterations) {
            if (maxToolIterations < 1) {
                throw new IllegalArgumentException("maxToolIterations must be at least 1, got " + maxToolIterations);
            }
            this.maxToolIterations = maxToolIterations;
            return this;
        }

        public Aegis4jEngine build() {
            return new Aegis4jEngine(this);
        }
    }
}
