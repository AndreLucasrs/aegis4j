package dev.aegis4j.core.engine;

import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.persona.Persona;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.CompletionRequest;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.provider.Provider;
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

    private final ProviderRegistry providerRegistry;
    private final GuardChain guardChain;
    private final SkillRegistry skillRegistry;
    private final SkillActivationStrategy activationStrategy;
    private final PersonaManager personaManager;
    private final Retriever retriever;
    private final ModelRouter modelRouter;
    private final PromptAssembler promptAssembler = new PromptAssembler();

    private Aegis4jEngine(Builder builder) {
        this.providerRegistry = builder.providerRegistry;
        this.guardChain = builder.guardChain;
        this.skillRegistry = builder.skillRegistry;
        this.activationStrategy = builder.activationStrategy;
        this.personaManager = builder.personaManager;
        this.retriever = builder.retriever;
        this.modelRouter = builder.modelRouter;
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

        CompletionResponse response = provider.complete(completionRequest);

        String sanitizedOutput = guardChain.runOutput(ctx, response.content());

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
     * v1 limitation: output guards need the full text, which is in tension
     * with token-by-token streaming. This method only runs input guards,
     * retrieval, routing and prompt assembly — output guards are NOT applied
     * to streamed chunks. Callers that need guaranteed output guarding must
     * use {@link #chat}.
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

        public Aegis4jEngine build() {
            return new Aegis4jEngine(this);
        }
    }
}
