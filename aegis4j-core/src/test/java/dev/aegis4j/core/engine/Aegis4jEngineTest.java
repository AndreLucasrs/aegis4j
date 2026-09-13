package dev.aegis4j.core.engine;

import dev.aegis4j.api.guard.Guard;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Aegis4jEngineTest {

    @Test
    void injectsSkillBodyOnlyWhenTriggerKeywordPresent() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        SkillRegistry skillRegistry = SkillRegistry.inMemory();
        skillRegistry.register(new ProgrammaticSkill(
                new SkillDescriptor("weather-explainer", "Explains weather concepts", List.of("weather", "forecast"))
        ) {
            @Override
            public String body() {
                return "FULL SKILL BODY";
            }
        });

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
}
