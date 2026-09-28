package dev.aegis4j.core.engine;

import dev.aegis4j.api.guard.Guard;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;
import dev.aegis4j.api.provider.CompletionChunk;
import dev.aegis4j.api.provider.CompletionRequest;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.FinishReason;
import dev.aegis4j.api.provider.ModelInfo;
import dev.aegis4j.api.provider.Provider;
import dev.aegis4j.api.provider.Usage;
import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.skill.ProgrammaticSkill;
import dev.aegis4j.api.skill.SkillDescriptor;
import dev.aegis4j.api.usage.UsageTracker;
import dev.aegis4j.core.guard.GuardChain;
import dev.aegis4j.core.routing.ModelRouter;
import dev.aegis4j.core.skill.SkillRegistry;
import dev.aegis4j.testkit.FakeProvider;
import dev.aegis4j.testkit.FakeRetriever;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Aegis4jEngineTest {

    private static SkillRegistry weatherSkillRegistry() {
        SkillRegistry skillRegistry = SkillRegistry.inMemory();
        skillRegistry.register(new ProgrammaticSkill(
                new SkillDescriptor("weather-explainer", "Explains weather concepts", List.of("weather", "forecast"))
        ) {
            @Override
            public String body() {
                return "FULL SKILL BODY";
            }
        });
        return skillRegistry;
    }

    @Test
    void injectsSkillBodyOnlyWhenTriggerKeywordPresent() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        SkillRegistry skillRegistry = weatherSkillRegistry();

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .skillRegistry(skillRegistry)
                .build();

        engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("what is the weather like").build());

        assertThat(provider.lastRequest().messages().toString()).contains("FULL SKILL BODY");

        engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("unrelated question").build());

        assertThat(provider.lastRequest().messages().toString()).doesNotContain("FULL SKILL BODY");
        assertThat(provider.lastRequest().messages().toString()).contains("weather-explainer");
    }

    @Test
    void skillCatalogIsPresentByDefaultEvenWithoutActivation() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        SkillRegistry skillRegistry = weatherSkillRegistry();

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .skillRegistry(skillRegistry)
                .build();

        engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("unrelated question").build());

        assertThat(provider.lastRequest().messages().toString())
                .contains("Available skills")
                .contains("weather-explainer")
                .doesNotContain("FULL SKILL BODY");
    }

    @Test
    void skillCatalogCanBeExcludedFromSystemPromptViaBuilderOption() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        SkillRegistry skillRegistry = weatherSkillRegistry();

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .skillRegistry(skillRegistry)
                .skillCatalogInSystemPrompt(false)
                .build();

        engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("unrelated question").build());

        assertThat(provider.lastRequest().messages().toString())
                .doesNotContain("Available skills")
                .doesNotContain("weather-explainer");

        engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("what is the weather like").build());

        assertThat(provider.lastRequest().messages().toString())
                .doesNotContain("Available skills")
                .contains("FULL SKILL BODY");
    }

    @Test
    void builderIsReusableAcrossMultipleBuildCalls() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        SkillRegistry skillRegistry = weatherSkillRegistry();

        Aegis4jEngine.Builder builder = Aegis4jEngine.builder()
                .provider(provider)
                .skillRegistry(skillRegistry)
                .skillCatalogInSystemPrompt(false);

        Aegis4jEngine first = builder.build();
        Aegis4jEngine second = builder.build();

        first.chat(ChatRequest.builder().providerId("fake").model("m").userInput("unrelated question").build());
        assertThat(provider.lastRequest().messages().toString()).doesNotContain("Available skills");

        second.chat(ChatRequest.builder().providerId("fake").model("m").userInput("unrelated question").build());
        assertThat(provider.lastRequest().messages().toString()).doesNotContain("Available skills");
    }

    @Test
    void runsInputAndOutputGuardChain() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("secret is abc123");
        Guard redactingGuard = new Guard() {
            @Override
            public String id() {
                return "test-redactor";
            }

            @Override
            public GuardResult checkInput(GuardContext ctx, String text) {
                return GuardResult.modify(text.replace("BAD", "***"), "input-redacted");
            }

            @Override
            public GuardResult checkOutput(GuardContext ctx, String text) {
                return GuardResult.modify(text.replace("abc123", "[REDACTED]"), "output-redacted");
            }
        };

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .guardChain(GuardChain.of(redactingGuard))
                .build();

        var response = engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("this is BAD input").build());

        assertThat(provider.lastRequest().messages().toString()).contains("***");
        assertThat(response.content()).isEqualTo("secret is [REDACTED]");
    }

    @Test
    void injectsRetrievedContextOnlyWhenRetrieverConfigured() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        FakeRetriever retriever = FakeRetriever.withChunks(
                List.of(new RetrievedChunk("relevant fact", "doc-1", 0.9, Map.of()))
        );

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .retriever(retriever)
                .build();

        engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("tell me about it").build());

        assertThat(provider.lastRequest().messages().toString()).contains("relevant fact").contains("doc-1");
        assertThat(retriever.receivedQueries()).containsExactly("tell me about it");
    }

    @Test
    void noContextBlockWhenNoRetrieverConfigured() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        Aegis4jEngine engine = Aegis4jEngine.builder().provider(provider).build();

        engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("tell me about it").build());

        assertThat(provider.lastRequest().messages().toString()).doesNotContain("Context:");
    }

    @Test
    void outputGuardsSeeRetrievedChunksViaGuardContext() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        FakeRetriever retriever = FakeRetriever.withChunks(
                List.of(new RetrievedChunk("relevant fact", "doc-1", 0.9, Map.of()))
        );
        List<RetrievedChunk> seenByGuard = new java.util.ArrayList<>();
        Guard capturingGuard = new Guard() {
            @Override
            public String id() {
                return "capture-chunks";
            }

            @Override
            public GuardResult checkInput(GuardContext ctx, String text) {
                // Input guards run before retrieval, so no chunks are available yet.
                assertThat(ctx.retrievedChunks()).isEmpty();
                return GuardResult.pass();
            }

            @Override
            public GuardResult checkOutput(GuardContext ctx, String text) {
                seenByGuard.addAll(ctx.retrievedChunks());
                return GuardResult.pass();
            }
        };

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .retriever(retriever)
                .guardChain(GuardChain.of(capturingGuard))
                .build();

        engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("tell me about it").build());

        assertThat(seenByGuard).extracting(RetrievedChunk::content).containsExactly("relevant fact");
    }

    @Test
    void explicitProviderAndModelWinOverRouting() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        ModelRouter router = new ModelRouter(List.of(), new RouteTarget("other-provider", "other-model"));

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .modelRouter(router)
                .build();

        engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("hi").build());

        assertThat(provider.lastRequest().model()).isEqualTo("m");
    }

    @Test
    void resolvesOmittedProviderAndModelFromRouter() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        ModelRouter router = new ModelRouter(
                List.of(ctx -> Optional.of(new RouteTarget("fake", "routed-model"))),
                new RouteTarget("fake", "default-model")
        );

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .modelRouter(router)
                .build();

        engine.chat(ChatRequest.builder().userInput("hi").build());

        assertThat(provider.lastRequest().model()).isEqualTo("routed-model");
    }

    @Test
    void throwsWhenProviderOrModelOmittedAndNoRouterConfigured() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        Aegis4jEngine engine = Aegis4jEngine.builder().provider(provider).build();

        assertThatThrownBy(() -> engine.chat(ChatRequest.builder().userInput("hi").build()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reportsUsageToConfiguredTrackerAfterChat() {
        Provider provider = new UsageReportingProvider("fake", new Usage(12, 34, 46));
        List<Usage> recorded = new ArrayList<>();
        UsageTracker tracker = (providerId, model, usage) -> {
            assertThat(providerId).isEqualTo("fake");
            assertThat(model).isEqualTo("m");
            recorded.add(usage);
        };

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .usageTracker(tracker)
                .build();

        engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("hi").build());

        assertThat(recorded).containsExactly(new Usage(12, 34, 46));
    }

    @Test
    void neverCallsUsageTrackerFromChatStream() {
        Provider provider = new UsageReportingProvider("fake", new Usage(1, 2, 3));
        List<Usage> recorded = new ArrayList<>();
        UsageTracker tracker = (providerId, model, usage) -> recorded.add(usage);

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .usageTracker(tracker)
                .build();

        engine.chatStream(ChatRequest.builder().providerId("fake").model("m").userInput("hi").build()).chunks().toList();

        assertThat(recorded).isEmpty();
    }

    @Test
    void worksWithoutUsageTrackerConfigured() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        Aegis4jEngine engine = Aegis4jEngine.builder().provider(provider).build();

        assertThat(engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("hi").build()).content())
                .isEqualTo("ok");
    }

    @Test
    void recordsUsageUnderTheRequestedModelNotWhateverTheProviderEchoesBack() {
        // Simulates a provider that resolves a caller-facing alias ("gpt-4o") to a
        // dated snapshot ("gpt-4o-2024-08-06") in its response, as OpenAI does.
        Provider provider = new UsageReportingProvider("fake", new Usage(1, 1, 2), "gpt-4o-2024-08-06");
        List<String> recordedModels = new ArrayList<>();
        UsageTracker tracker = (providerId, model, usage) -> recordedModels.add(model);

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .usageTracker(tracker)
                .build();

        engine.chat(ChatRequest.builder().providerId("fake").model("gpt-4o").userInput("hi").build());

        assertThat(recordedModels).containsExactly("gpt-4o");
    }

    @Test
    void treatsNullUsageFromProviderAsUnknownInsteadOfFailing() {
        Provider provider = new UsageReportingProvider("fake", null);
        List<Usage> recorded = new ArrayList<>();
        UsageTracker tracker = (providerId, model, usage) -> recorded.add(usage);

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .usageTracker(tracker)
                .build();

        var response = engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("hi").build());

        assertThat(response.content()).isEqualTo("ok");
        assertThat(recorded).containsExactly(Usage.UNKNOWN);
    }

    @Test
    void usageTrackerFailureDoesNotFailChat() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        UsageTracker failingTracker = (providerId, model, usage) -> {
            throw new RuntimeException("billing API is down");
        };

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .usageTracker(failingTracker)
                .build();

        var response = engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("hi").build());

        assertThat(response.content()).isEqualTo("ok");
    }

    /** Minimal {@link Provider} double that returns a caller-supplied {@link Usage} instead of {@link Usage#UNKNOWN}. */
    private static final class UsageReportingProvider implements Provider {
        private final String id;
        private final Usage usage;
        private final String responseModelOverride;

        UsageReportingProvider(String id, Usage usage) {
            this(id, usage, null);
        }

        /** {@code responseModelOverride}, when non-null, simulates a provider echoing back a different model string than what was requested. */
        UsageReportingProvider(String id, Usage usage, String responseModelOverride) {
            this.id = id;
            this.usage = usage;
            this.responseModelOverride = responseModelOverride;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            String model = responseModelOverride != null ? responseModelOverride : request.model();
            return new CompletionResponse("resp-1", model, "ok", FinishReason.STOP, usage, List.of());
        }

        @Override
        public Stream<CompletionChunk> stream(CompletionRequest request) {
            return Stream.of(CompletionChunk.ofDelta("ok"), CompletionChunk.finished());
        }

        @Override
        public List<ModelInfo> listModels() {
            return List.of(new ModelInfo(id, id, 8192));
        }
    }
}
