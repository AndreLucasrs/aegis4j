package dev.aegis4j.core.engine;

import dev.aegis4j.api.guard.Guard;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.core.guard.GuardBlockedException;
import dev.aegis4j.core.guard.GuardChain;
import dev.aegis4j.core.observability.EngineListener;
import dev.aegis4j.testkit.FakeProvider;
import dev.aegis4j.testkit.FakeRetriever;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies {@link Aegis4jEngine} drives {@link EngineListener} through the pipeline, and isolates its failures. */
class EngineListenerTest {

    /** Records every callback invocation, in call order, for assertion. */
    private static final class RecordingListener implements EngineListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onChatStarted(String requestId) {
            events.add("onChatStarted:" + requestId);
        }

        @Override
        public void onInputGuardComplete(String requestId, String sanitizedInput) {
            events.add("onInputGuardComplete:" + sanitizedInput);
        }

        @Override
        public void onRetrievalComplete(String requestId, List<RetrievedChunk> chunks) {
            events.add("onRetrievalComplete:" + chunks.size());
        }

        @Override
        public void onRouteResolved(String requestId, String providerId, String model) {
            events.add("onRouteResolved:" + providerId + "/" + model);
        }

        @Override
        public void onProviderCallComplete(String requestId, CompletionResponse response, Duration duration) {
            assertThat(duration).isNotNull();
            events.add("onProviderCallComplete:" + response.content());
        }

        @Override
        public void onOutputGuardComplete(String requestId, String sanitizedOutput) {
            events.add("onOutputGuardComplete:" + sanitizedOutput);
        }

        @Override
        public void onChatComplete(String requestId, Duration totalDuration) {
            assertThat(totalDuration).isNotNull();
            events.add("onChatComplete");
        }

        @Override
        public void onChatFailed(String requestId, Throwable error, Duration totalDuration) {
            events.add("onChatFailed:" + error.getClass().getSimpleName());
        }
    }

    @Test
    void invokesListenerForEveryPipelinePhaseInOrder() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("hi there");
        FakeRetriever retriever = FakeRetriever.withChunks(
                List.of(new RetrievedChunk("fact", "doc-1", 0.9, Map.of())));
        RecordingListener listener = new RecordingListener();

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .retriever(retriever)
                .listener(listener)
                .build();

        CompletionResponse response = engine.chat(ChatRequest.builder()
                .requestId("req-1").providerId("fake").model("m").userInput("tell me").build());

        assertThat(listener.events).containsExactly(
                "onChatStarted:req-1",
                "onInputGuardComplete:tell me",
                "onRetrievalComplete:1",
                "onRouteResolved:fake/m",
                "onProviderCallComplete:hi there",
                "onOutputGuardComplete:hi there",
                "onChatComplete"
        );
        assertThat(response.content()).isEqualTo("hi there");
    }

    @Test
    void skipsRetrievalCallbackWhenNoRetrieverConfigured() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        RecordingListener listener = new RecordingListener();

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .listener(listener)
                .build();

        engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("hi").build());

        assertThat(listener.events).noneMatch(event -> event.startsWith("onRetrievalComplete"));
    }

    @Test
    void notifiesOnChatFailedInsteadOfOnChatCompleteWhenGuardBlocks() {
        Guard blockingGuard = new Guard() {
            @Override
            public String id() {
                return "blocker";
            }

            @Override
            public GuardResult checkInput(GuardContext ctx, String text) {
                return GuardResult.block("blocked", "nope");
            }

            @Override
            public GuardResult checkOutput(GuardContext ctx, String text) {
                return GuardResult.pass();
            }
        };
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        RecordingListener listener = new RecordingListener();

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .guardChain(GuardChain.of(blockingGuard))
                .listener(listener)
                .build();

        assertThatThrownBy(() -> engine.chat(ChatRequest.builder()
                .requestId("req-blocked").providerId("fake").model("m").userInput("hi").build()))
                .isInstanceOf(GuardBlockedException.class);

        assertThat(listener.events).containsExactly(
                "onChatStarted:req-blocked",
                "onChatFailed:GuardBlockedException"
        );
    }

    @Test
    void listenerFailuresAreIsolatedAndDoNotBreakTheResponseOrOtherListeners() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        RecordingListener recordingListener = new RecordingListener();
        EngineListener throwingListener = new EngineListener() {
            @Override
            public void onChatStarted(String requestId) {
                throw new RuntimeException("boom on start");
            }

            @Override
            public void onProviderCallComplete(String requestId, CompletionResponse response, Duration duration) {
                throw new IllegalStateException("boom on provider call");
            }
        };

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .listener(throwingListener)
                .listener(recordingListener)
                .build();

        CompletionResponse response = engine.chat(ChatRequest.builder()
                .providerId("fake").model("m").userInput("hi").build());

        assertThat(response.content()).isEqualTo("ok");
        assertThat(recordingListener.events).contains(
                "onInputGuardComplete:hi", "onProviderCallComplete:ok", "onChatComplete"
        );
    }

    @Test
    void listenerErrorsAreIsolatedTooNotJustRuntimeExceptions() {
        // A listener throwing an Error (not just a RuntimeException) - e.g. a
        // StackOverflowError from a buggy recursive attribute mapper - must not
        // break the pipeline either, and in particular must not prevent
        // onChatStarted's later pairing with onChatComplete/onChatFailed.
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        RecordingListener recordingListener = new RecordingListener();
        EngineListener throwingListener = new EngineListener() {
            @Override
            public void onChatStarted(String requestId) {
                throw new StackOverflowError("boom on start");
            }

            @Override
            public void onChatComplete(String requestId, Duration totalDuration) {
                throw new NoClassDefFoundError("boom on complete");
            }
        };

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .listener(throwingListener)
                .listener(recordingListener)
                .build();

        CompletionResponse response = engine.chat(ChatRequest.builder()
                .requestId("req-error-iso").providerId("fake").model("m").userInput("hi").build());

        assertThat(response.content()).isEqualTo("ok");
        assertThat(recordingListener.events).containsExactly(
                "onChatStarted:req-error-iso",
                "onInputGuardComplete:hi",
                "onRouteResolved:fake/m",
                "onProviderCallComplete:ok",
                "onOutputGuardComplete:ok",
                "onChatComplete"
        );
    }

    @Test
    void chatStreamNotifiesSetupPhasesAndClosesOutWithOnChatCompleteButNoOutputGuard() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        RecordingListener listener = new RecordingListener();

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .listener(listener)
                .build();

        engine.chatStream(ChatRequest.builder()
                .requestId("req-2").providerId("fake").model("m").userInput("hi").build())
                .forEach(chunk -> { });

        assertThat(listener.events).containsExactly(
                "onChatStarted:req-2",
                "onInputGuardComplete:hi",
                "onRouteResolved:fake/m",
                "onChatComplete"
        );
    }

    @Test
    void listenersMethodRegistersSeveralListenersAtOnce() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .listeners(List.of(first, second))
                .build();

        engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("hi").build());

        assertThat(first.events).contains("onChatComplete");
        assertThat(second.events).contains("onChatComplete");
    }
}
