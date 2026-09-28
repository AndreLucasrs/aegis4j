package dev.aegis4j.core.engine;

import dev.aegis4j.api.guard.Guard;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.FinishReason;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.provider.Role;
import dev.aegis4j.api.provider.ToolCall;
import dev.aegis4j.api.provider.ToolDefinition;
import dev.aegis4j.api.provider.Usage;
import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.skill.ProgrammaticSkill;
import dev.aegis4j.api.skill.SkillDescriptor;
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
import java.util.concurrent.atomic.AtomicInteger;

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
    void toolCallsAreIgnoredWhenNoToolExecutorIsConfigured() {
        ToolCall call = new ToolCall("call-1", "get_weather", "{}");
        FakeProvider provider = FakeProvider.withId("fake").respondingWithFullResponse(request ->
                new CompletionResponse("id", request.model(), "here", FinishReason.TOOL_CALLS, Usage.UNKNOWN, List.of(call)));

        Aegis4jEngine engine = Aegis4jEngine.builder().provider(provider).build();

        CompletionResponse response = engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("what's the weather").build());

        assertThat(response.toolCalls()).containsExactly(call);
        assertThat(provider.receivedRequests()).hasSize(1);
        assertThat(provider.lastRequest().tools()).isEmpty();
    }

    @Test
    void executesToolCallAndReinjectsResultUntilFinalAnswer() {
        ToolDefinition weatherTool = new ToolDefinition("get_weather", "Looks up the weather", Map.of());
        ToolCall call = new ToolCall("call-1", "get_weather", "{\"city\":\"NYC\"}");

        FakeProvider provider = FakeProvider.withId("fake").respondingWithFullResponse(request ->
                request.messages().stream().anyMatch(m -> m.role() == Role.TOOL)
                        ? new CompletionResponse("id-2", request.model(), "It's sunny in NYC", FinishReason.STOP, Usage.UNKNOWN, List.of())
                        : new CompletionResponse("id-1", request.model(), null, FinishReason.TOOL_CALLS, Usage.UNKNOWN, List.of(call)));

        List<ToolCall> executedCalls = new ArrayList<>();
        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .tools(List.of(weatherTool), toolCall -> {
                    executedCalls.add(toolCall);
                    return "sunny, 22C";
                })
                .build();

        CompletionResponse response = engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("what's the weather in NYC").build());

        assertThat(response.content()).isEqualTo("It's sunny in NYC");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(executedCalls).containsExactly(call);

        assertThat(provider.receivedRequests()).hasSize(2);
        assertThat(provider.receivedRequests().get(0).tools()).containsExactly(weatherTool);

        List<Message> secondRequestMessages = provider.receivedRequests().get(1).messages();
        assertThat(secondRequestMessages)
                .anySatisfy(m -> {
                    assertThat(m.role()).isEqualTo(Role.ASSISTANT);
                    assertThat(m.toolCalls()).containsExactly(call);
                })
                .anySatisfy(m -> {
                    assertThat(m.role()).isEqualTo(Role.TOOL);
                    assertThat(m.toolCallId()).isEqualTo("call-1");
                    assertThat(m.content()).isEqualTo("sunny, 22C");
                });
    }

    @Test
    void toolExecutorFailureIsFedBackToTheModelInsteadOfAbortingTheExchange() {
        ToolDefinition failingTool = new ToolDefinition("broken_tool", "Always fails", Map.of());
        ToolCall call = new ToolCall("call-1", "broken_tool", "{}");

        FakeProvider provider = FakeProvider.withId("fake").respondingWithFullResponse(request ->
                request.messages().stream().anyMatch(m -> m.role() == Role.TOOL)
                        ? new CompletionResponse("id-2", request.model(), "handled the failure", FinishReason.STOP, Usage.UNKNOWN, List.of())
                        : new CompletionResponse("id-1", request.model(), null, FinishReason.TOOL_CALLS, Usage.UNKNOWN, List.of(call)));

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .tools(List.of(failingTool), toolCall -> {
                    throw new IllegalStateException("boom");
                })
                .build();

        CompletionResponse response = engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("break it").build());

        assertThat(response.content()).isEqualTo("handled the failure");
        List<Message> secondRequestMessages = provider.receivedRequests().get(1).messages();
        assertThat(secondRequestMessages)
                .anySatisfy(m -> {
                    assertThat(m.role()).isEqualTo(Role.TOOL);
                    assertThat(m.content()).contains("broken_tool").contains("IllegalStateException");
                    assertThat(m.content()).doesNotContain("boom");
                });
    }

    @Test
    void throwsWhenToolCallLoopExceedsMaxIterations() {
        ToolDefinition loopingTool = new ToolDefinition("looping_tool", "Never stops calling itself", Map.of());
        AtomicInteger callCount = new AtomicInteger();

        FakeProvider provider = FakeProvider.withId("fake").respondingWithFullResponse(request -> {
            ToolCall call = new ToolCall("call-" + callCount.incrementAndGet(), "looping_tool", "{}");
            return new CompletionResponse("id", request.model(), null, FinishReason.TOOL_CALLS, Usage.UNKNOWN, List.of(call));
        });

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .tools(List.of(loopingTool), toolCall -> "result")
                .maxToolIterations(2)
                .build();

        assertThatThrownBy(() -> engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("loop forever").build()))
                .isInstanceOf(ToolCallLimitExceededException.class);
    }

    @Test
    void maxToolIterationsCapsTotalProviderCallsIncludingTheInitialOne() {
        ToolDefinition loopingTool = new ToolDefinition("looping_tool", "Never stops calling itself", Map.of());
        AtomicInteger callCount = new AtomicInteger();

        FakeProvider provider = FakeProvider.withId("fake").respondingWithFullResponse(request -> {
            ToolCall call = new ToolCall("call-" + callCount.incrementAndGet(), "looping_tool", "{}");
            return new CompletionResponse("id", request.model(), null, FinishReason.TOOL_CALLS, Usage.UNKNOWN, List.of(call));
        });

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .tools(List.of(loopingTool), toolCall -> "result")
                .maxToolIterations(3)
                .build();

        assertThatThrownBy(() -> engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("loop forever").build()))
                .isInstanceOf(ToolCallLimitExceededException.class);

        assertThat(provider.receivedRequests()).hasSize(3);
    }

    @Test
    void guardChainDoesNotNpeWhenFinalResponseContentIsNull() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWithFullResponse(request ->
                new CompletionResponse("id", request.model(), null, FinishReason.STOP, Usage.UNKNOWN, List.of()));

        Guard nullSafetyProbe = new Guard() {
            @Override
            public String id() {
                return "null-safety-probe";
            }

            @Override
            public GuardResult checkInput(GuardContext ctx, String text) {
                return GuardResult.pass();
            }

            @Override
            public GuardResult checkOutput(GuardContext ctx, String text) {
                return GuardResult.modify(text + "[checked]", "appended");
            }
        };

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .guardChain(GuardChain.of(nullSafetyProbe))
                .build();

        CompletionResponse response = engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("hi").build());

        assertThat(response.content()).isEqualTo("[checked]");
    }

    @Test
    void toolsThrowsWhenDefinitionsProvidedWithoutAnExecutor() {
        ToolDefinition tool = new ToolDefinition("some_tool", "does something", Map.of());
        Aegis4jEngine.Builder builder = Aegis4jEngine.builder();

        assertThatThrownBy(() -> builder.tools(List.of(tool), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolsIsANoOpWhenBothDefinitionsAndExecutorAreNull() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .tools(null, null)
                .build();

        engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("hi").build());

        assertThat(provider.lastRequest().tools()).isEmpty();
    }
}
