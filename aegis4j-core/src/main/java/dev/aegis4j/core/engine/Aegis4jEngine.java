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
import dev.aegis4j.core.observability.EngineListener;
import dev.aegis4j.core.persona.PersonaManager;
import dev.aegis4j.core.prompt.PromptAssembler;
import dev.aegis4j.core.provider.ProviderRegistry;
import dev.aegis4j.core.routing.ModelRouter;
import dev.aegis4j.core.skill.KeywordSkillActivationStrategy;
import dev.aegis4j.core.skill.SkillActivationStrategy;
import dev.aegis4j.core.skill.SkillRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Wires provider, guards, skills, persona, retrieval and routing into one
 * request pipeline: input guards → retrieval (if configured) → route
 * resolution (if provider/model absent) → prompt assembly → provider call →
 * output guards.
 */
public final class Aegis4jEngine {

    private static final System.Logger LOGGER = System.getLogger(Aegis4jEngine.class.getName());
    private static final int DEFAULT_TOP_K = 4;

    private final ProviderRegistry providerRegistry;
    private final GuardChain guardChain;
    private final SkillRegistry skillRegistry;
    private final SkillActivationStrategy activationStrategy;
    private final boolean includeSkillCatalogInSystemPrompt;
    private final PersonaManager personaManager;
    private final Retriever retriever;
    private final ModelRouter modelRouter;
    private final List<EngineListener> listeners;
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
        this.listeners = List.copyOf(builder.listeners);
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletionResponse chat(ChatRequest request) {
        String requestId = request.requestId();
        Instant chatStart = Instant.now();
        try {
            notifyListeners(l -> l.onChatStarted(requestId));

            Pipeline pipeline = runPipeline(requestId, request);

            Provider provider = providerRegistry.resolve(pipeline.route().providerId());
            Instant providerCallStart = Instant.now();
            CompletionResponse response = provider.complete(pipeline.completionRequest());
            Duration providerCallDuration = Duration.between(providerCallStart, Instant.now());
            notifyListeners(l -> l.onProviderCallComplete(requestId, response, providerCallDuration));

            String sanitizedOutput = guardChain.runOutput(pipeline.guardContext(), response.content());
            notifyListeners(l -> l.onOutputGuardComplete(requestId, sanitizedOutput));

            CompletionResponse result = new CompletionResponse(
                    response.id(),
                    response.model(),
                    sanitizedOutput,
                    response.finishReason(),
                    response.usage(),
                    response.toolCalls()
            );
            notifyListeners(l -> l.onChatComplete(requestId, Duration.between(chatStart, Instant.now())));
            return result;
        } catch (RuntimeException e) {
            Duration failedDuration = Duration.between(chatStart, Instant.now());
            notifyListeners(l -> l.onChatFailed(requestId, e, failedDuration));
            throw e;
        }
    }

    /**
     * v1 limitation: output guards need the full text, which is in tension
     * with token-by-token streaming. This method only runs input guards,
     * retrieval, routing and prompt assembly — output guards are NOT applied
     * to streamed chunks. Callers that need guaranteed output guarding must
     * use {@link #chat}.
     *
     * <p>For the same reason, {@link EngineListener} never sees
     * {@code onOutputGuardComplete} or {@code onProviderCallComplete} here —
     * output guards don't run in this method, and the provider call itself
     * is {@link Provider#stream}, not {@link Provider#complete}. {@code
     * onChatComplete}/{@code onChatFailed} still fire (so every {@code
     * onChatStarted} is reliably paired with exactly one of the two, letting
     * a listener close out per-request state such as a span), but their
     * duration only covers this synchronous setup — resolving the request
     * into a {@link CompletionRequest} and obtaining the {@link Stream} from
     * the provider — not the caller's later consumption of that stream.
     */
    public Stream<dev.aegis4j.api.provider.CompletionChunk> chatStream(ChatRequest request) {
        String requestId = request.requestId();
        Instant chatStart = Instant.now();
        try {
            notifyListeners(l -> l.onChatStarted(requestId));

            Pipeline pipeline = runPipeline(requestId, request);

            Provider provider = providerRegistry.resolve(pipeline.route().providerId());
            Stream<dev.aegis4j.api.provider.CompletionChunk> stream = provider.stream(pipeline.completionRequest());
            notifyListeners(l -> l.onChatComplete(requestId, Duration.between(chatStart, Instant.now())));
            return stream;
        } catch (RuntimeException e) {
            Duration failedDuration = Duration.between(chatStart, Instant.now());
            notifyListeners(l -> l.onChatFailed(requestId, e, failedDuration));
            throw e;
        }
    }

    /**
     * The pipeline segment shared by {@link #chat} and {@link #chatStream}:
     * input guard → retrieval (if configured) → route resolution → prompt
     * assembly → {@link CompletionRequest} assembly, firing the matching
     * {@link EngineListener} callback after each phase. Kept as a single
     * method specifically so the two callers can't drift apart on which
     * callbacks they fire for this shared portion of the pipeline.
     */
    private Pipeline runPipeline(String requestId, ChatRequest request) {
        GuardContext inputCtx = new GuardContext(request.requestId(), request.userId(), Map.of());

        String sanitizedInput = guardChain.runInput(inputCtx, request.userInput());
        notifyListeners(l -> l.onInputGuardComplete(requestId, sanitizedInput));

        List<RetrievedChunk> retrievedChunks = resolveChunks(sanitizedInput, request);
        if (retriever != null) {
            notifyListeners(l -> l.onRetrievalComplete(requestId, retrievedChunks));
        }
        ResolvedRoute route = resolveRoute(sanitizedInput, request);
        notifyListeners(l -> l.onRouteResolved(requestId, route.providerId(), route.model()));

        List<Message> messages = promptAssembler.assemble(
                personaManager.active(),
                skillRegistry,
                activationStrategy,
                includeSkillCatalogInSystemPrompt,
                retrievedChunks,
                request.history(),
                sanitizedInput
        );

        CompletionRequest completionRequest = CompletionRequest.builder()
                .model(route.model())
                .messages(messages)
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .build();

        // Output guards (e.g. a grounding/hallucination judge) need the RAG
        // context, which is only known after the input-guard ctx above was
        // built — hence a separate GuardContext here rather than reusing
        // inputCtx. chatStream() never reads Pipeline.guardContext() since it
        // doesn't run output guards, so this costs nothing on that path.
        GuardContext outputCtx = new GuardContext(request.requestId(), request.userId(),
                Map.of(GuardContext.RETRIEVED_CHUNKS_KEY, retrievedChunks));

        return new Pipeline(outputCtx, route, completionRequest);
    }

    private record Pipeline(GuardContext guardContext, ResolvedRoute route, CompletionRequest completionRequest) {
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

    /**
     * Isolates listener failures from the pipeline: a listener must never be
     * able to break (or alter) the real response, so whatever it throws —
     * including an {@link Error} such as {@link StackOverflowError}, not
     * just a {@link RuntimeException} — is caught and logged, never
     * propagated. This makes every {@code notifyListeners} call (including
     * the {@code onChatStarted} one) inherently safe to call from inside
     * {@link #chat}/{@link #chatStream}'s {@code try} block: it can never be
     * the reason an {@code onChatStarted} goes unpaired with an
     * {@code onChatComplete}/{@code onChatFailed}.
     */
    private void notifyListeners(Consumer<EngineListener> callback) {
        for (EngineListener listener : listeners) {
            try {
                callback.accept(listener);
            } catch (RuntimeException | Error e) {
                LOGGER.log(System.Logger.Level.WARNING, "EngineListener threw an exception; ignoring", e);
            }
        }
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
        private final List<EngineListener> listeners = new ArrayList<>();

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
         * Registers an opt-in {@link EngineListener} for pipeline
         * instrumentation (tracing, metrics, ...). Accumulates: may be
         * called more than once to register several listeners.
         */
        public Builder listener(EngineListener listener) {
            this.listeners.add(listener);
            return this;
        }

        /** Registers several {@link EngineListener}s at once; see {@link #listener(EngineListener)}. */
        public Builder listeners(List<EngineListener> listeners) {
            this.listeners.addAll(listeners);
            return this;
        }

        public Aegis4jEngine build() {
            return new Aegis4jEngine(this);
        }
    }
}
